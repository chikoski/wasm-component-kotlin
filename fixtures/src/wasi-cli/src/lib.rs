wit_bindgen::generate!({
    world: "example:wasi-cli/wasi-cli@0.1.0",
    path: ["../../../runtime-wasi/wit", "wit"],
    generate_all,
});

use crate::wasi::cli::{environment, exit, stderr, stdin, stdout};
use crate::wasi::filesystem::{
    preopens,
    types::{Descriptor, DescriptorFlags, OpenFlags, PathFlags},
};
use wit_bindgen::spawn_local;

struct Component;

async fn write_stdout(text: String) {
    let (mut tx, rx) = wit_stream::new::<u8>();
    spawn_local(async move {
        let _ = tx.write_all(text.into_bytes()).await;
    });
    let _ = stdout::write_via_stream(rx).await;
}

async fn write_stderr(text: String) {
    let (mut tx, rx) = wit_stream::new::<u8>();
    spawn_local(async move {
        let _ = tx.write_all(text.into_bytes()).await;
    });
    let _ = stderr::write_via_stream(rx).await;
}

fn resolve_path<'a>(
    preopened: &'a [(Descriptor, String)],
    raw: &'a str,
) -> Option<(&'a Descriptor, &'a str)> {
    if !raw.starts_with('/') {
        return preopened.first().map(|(dir, _)| (dir, raw));
    }
    let mut best: Option<(usize, &'a Descriptor, &'a str)> = None;
    for (dir, mount) in preopened {
        let trimmed = mount.trim_end_matches('/');
        if trimmed.is_empty() && mount.starts_with('/') {
            let rel = raw.trim_start_matches('/');
            if best.map_or(true, |(best_score, _, _)| 1 > best_score) {
                best = Some((1, dir, rel));
            }
        } else if !trimmed.is_empty() {
            let norm = trimmed.trim_start_matches('/');
            if let Some(rest) = raw.trim_start_matches('/').strip_prefix(norm) {
                if rest.is_empty() || rest.starts_with('/') {
                    let rel = rest.trim_start_matches('/');
                    let score = norm.len() + 1;
                    if best.map_or(true, |(best_score, _, _)| score > best_score) {
                        best = Some((score, dir, rel));
                    }
                }
            }
        }
    }
    best.map(|(_, dir, rel)| (dir, rel))
}

async fn parse_exit_code(arg: Option<&String>, flag: &str) -> Result<u8, ()> {
    let Some(raw) = arg else {
        write_stderr(format!("usage: wasi-cli {flag} <0..255>\n")).await;
        return Err(());
    };
    match raw.parse::<u8>() {
        Ok(code) => Ok(code),
        Err(_) => {
            write_stderr(format!("error: invalid exit code '{raw}'\n")).await;
            Err(())
        }
    }
}

fn trigger_exit(code: u8) {
    if code == 0 {
        exit::exit(Ok(()));
    } else {
        exit::exit_with_code(code);
    }
}

impl exports::wasi::cli::run::Guest for Component {
    async fn run() -> Result<(), ()> {
        let args = environment::get_arguments();
        match args.get(1).map(String::as_str) {
            Some("--exit") => {
                let code = parse_exit_code(args.get(2), "--exit").await?;
                trigger_exit(code);
                return Err(());
            }
            Some("--spawn-exit") => {
                let code = parse_exit_code(args.get(2), "--spawn-exit").await?;
                let (tx, mut rx) = wit_stream::new::<u8>();
                spawn_local(async move {
                    let _keep_tx = tx;
                    trigger_exit(code);
                });
                let _ = rx.next().await;
                return Err(());
            }
            _ => {}
        }

        if args.len() < 3 {
            write_stderr("usage: wasi-cli <input-path> <output-path>\n".to_string()).await;
            return Err(());
        }
        let input_path = &args[1];
        let output_path = &args[2];

        let tag = environment::get_environment()
            .into_iter()
            .find(|(k, _)| k == "TAG")
            .map(|(_, v)| v)
            .unwrap_or_else(|| "processed".to_string());

        let (stdin_stream, stdin_result) = stdin::read_via_stream();
        let stdin_bytes = stdin_stream.collect().await;
        let _ = stdin_result.await;
        let footer = String::from_utf8_lossy(&stdin_bytes).trim().to_string();

        let preopened = preopens::get_directories();
        if preopened.is_empty() {
            write_stderr("error: no preopened directory configured\n".to_string()).await;
            return Err(());
        }
        let Some((in_root, in_rel)) = resolve_path(&preopened, input_path) else {
            write_stderr(format!("error: no preopened directory matches '{input_path}'\n")).await;
            return Err(());
        };
        let Some((out_root, out_rel)) = resolve_path(&preopened, output_path) else {
            write_stderr(format!("error: no preopened directory matches '{output_path}'\n")).await;
            return Err(());
        };

        let in_file = match in_root
            .open_at(
                PathFlags::empty(),
                in_rel.to_string(),
                OpenFlags::empty(),
                DescriptorFlags::READ,
            )
            .await
        {
            Ok(desc) => desc,
            Err(code) => {
                write_stderr(format!("error: failed to open '{input_path}': {code:?}\n")).await;
                return Err(());
            }
        };

        let (in_stream, in_read_res) = in_file.read_via_stream(0);
        let raw_bytes = in_stream.collect().await;
        if let Err(code) = in_read_res.await {
            write_stderr(format!("error: failed to read '{input_path}': {code:?}\n")).await;
            return Err(());
        }

        let input_text = match String::from_utf8(raw_bytes) {
            Ok(text) => text,
            Err(err) => {
                write_stderr(format!("error: '{input_path}' is not valid UTF-8: {err}\n")).await;
                return Err(());
            }
        };

        let mut lines_out = Vec::new();
        for (idx, line) in input_text
            .lines()
            .map(str::trim_end)
            .filter(|line| !line.is_empty())
            .enumerate()
        {
            lines_out.push(format!("[{tag}] {}: {line}", idx + 1));
        }
        let line_count = lines_out.len();
        if !footer.is_empty() {
            lines_out.push(format!("# footer: {footer}"));
        }
        let mut rendered = lines_out.join("\n");
        if !rendered.is_empty() {
            rendered.push('\n');
        }

        let out_file = match out_root
            .open_at(
                PathFlags::empty(),
                out_rel.to_string(),
                OpenFlags::CREATE | OpenFlags::TRUNCATE,
                DescriptorFlags::READ | DescriptorFlags::WRITE,
            )
            .await
        {
            Ok(desc) => desc,
            Err(code) => {
                write_stderr(format!("error: failed to open '{output_path}': {code:?}\n")).await;
                return Err(());
            }
        };

        let (mut out_tx, out_rx) = wit_stream::new::<u8>();
        spawn_local(async move {
            let _ = out_tx.write_all(rendered.into_bytes()).await;
        });
        if let Err(code) = out_file.write_via_stream(out_rx, 0).await {
            write_stderr(format!("error: failed to write '{output_path}': {code:?}\n")).await;
            return Err(());
        }

        write_stdout(format!(
            "wrote {line_count} line(s) from {input_path} to {output_path} tag={tag}\n"
        ))
        .await;
        Ok(())
    }
}

export!(Component);
