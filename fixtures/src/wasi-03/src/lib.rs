wit_bindgen::generate!({
    world: "wasi-app",
    generate_all,
});

use example::wasi_app::audit;
use wasi::cli::{environment, exit, stdin, stdout};
use wasi::clocks::{monotonic_clock, system_clock};
use wasi::filesystem::{preopens, types as fs_types};
use wasi::random::random;

struct Component;

impl Guest for Component {
    async fn run_summary() -> String {
        audit::record_step("start");

        let rand_val = random::get_random_u64();
        let t0 = monotonic_clock::now();
        monotonic_clock::wait_for(1_000_000).await;
        let t1 = monotonic_clock::now();
        let wall = system_clock::now();

        let args = environment::get_arguments();
        let env_pairs = environment::get_environment();

        let (stdin_rx, stdin_done) = stdin::read_via_stream();
        let stdin_bytes = stdin_rx.collect().await;
        let stdin_res = stdin_done.await;
        let stdin_ok = stdin_res.is_ok();
        let stdin_text = String::from_utf8_lossy(&stdin_bytes).into_owned();

        let stdout_payload = format!("out:{stdin_text}").into_bytes();
        let (mut stdout_tx, stdout_rx) = wit_stream::new();
        let stdout_done = stdout::write_via_stream(stdout_rx);
        let remaining = stdout_tx.write_all(stdout_payload).await;
        drop(stdout_tx);
        let stdout_ok = remaining.is_empty() && stdout_done.await.is_ok();

        let dirs = preopens::get_directories();
        let fs_summary = if let Some((root_dir, mount_path)) = dirs.into_iter().next() {
            let input_file = root_dir
                .open_at(
                    fs_types::PathFlags::empty(),
                    "input.txt".to_string(),
                    fs_types::OpenFlags::empty(),
                    fs_types::DescriptorFlags::READ,
                )
                .await;
            match input_file {
                Ok(file) => {
                    let (file_rx, file_done) = file.read_via_stream(0);
                    let mut content = file_rx.collect().await;
                    let read_ok = file_done.await.is_ok();
                    content.push(b'!');

                    let out_file = root_dir
                        .open_at(
                            fs_types::PathFlags::empty(),
                            "output.txt".to_string(),
                            fs_types::OpenFlags::CREATE | fs_types::OpenFlags::TRUNCATE,
                            fs_types::DescriptorFlags::WRITE,
                        )
                        .await;
                    let write_ok = match out_file {
                        Ok(out) => {
                            let (mut out_tx, out_rx) = wit_stream::new();
                            let out_done = out.write_via_stream(out_rx, 0);
                            let left = out_tx.write_all(content.clone()).await;
                            drop(out_tx);
                            left.is_empty() && out_done.await.is_ok()
                        }
                        Err(_) => false,
                    };
                    let text = String::from_utf8_lossy(&content);
                    format!(
                        "mount={mount_path},read_ok={read_ok},write_ok={write_ok},data={text}"
                    )
                }
                Err(code) => format!("mount={mount_path},open_err={code:?}"),
            }
        } else {
            "none".to_string()
        };

        audit::record_step("done");

        let mono_ok = t1 >= t0;
        let wall_ok = wall.nanoseconds < 1_000_000_000;
        let env_joined = env_pairs
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join(",");

        format!(
            "rand={rand_val};mono={mono_ok};wall={wall_ok};args={};env={env_joined};stdin_ok={stdin_ok};stdout_ok={stdout_ok};stdin={stdin_text};fs=[{fs_summary}]",
            args.join(",")
        )
    }

    fn exit_with(code: u8) {
        exit::exit_with_code(code);
    }
}

export!(Component);
