wit_bindgen::generate!({
    world: "wasi-cli",
    generate_all,
});

use crate::wasi::cli::{environment, stderr, stdin, stdout};
use crate::wasi::filesystem::{
    preopens,
    types::{DescriptorFlags, OpenFlags, PathFlags},
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

impl exports::wasi::cli::run::Guest for Component {
    async fn run() -> Result<(), ()> {
        let args = environment::get_arguments();
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
        let Some((root_dir, _mount)) = preopened.first() else {
            write_stderr("error: no preopened directory configured\n".to_string()).await;
            return Err(());
        };

        let in_file = match root_dir
            .open_at(
                PathFlags::empty(),
                input_path.clone(),
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

        let out_file = match root_dir
            .open_at(
                PathFlags::empty(),
                output_path.clone(),
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
