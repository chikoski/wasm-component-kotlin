wit_bindgen::generate!({
    world: "async-pipeline",
    generate_all,
});

use wit_bindgen::{spawn_local, FutureReader, StreamReader};

struct AsyncPipeline;

impl Guest for AsyncPipeline {
    async fn produce_numbers(start: u32, count: u32) -> StreamReader<u32> {
        let (mut tx, rx) = wit_stream::new::<u32>();
        spawn_local(async move {
            let chunk: Vec<u32> = (0..count).map(|offset| start.saturating_add(offset)).collect();
            if !chunk.is_empty() {
                let _ = tx.write_all(chunk).await;
            }
        });
        rx
    }

    async fn process_items(
        mut items: StreamReader<String>,
    ) -> (StreamReader<String>, FutureReader<u32>) {
        let (mut out_tx, out_rx) = wit_stream::new::<String>();
        let (count_tx, count_rx) = wit_future::new::<u32>(|| 0);

        spawn_local(async move {
            let mut count = 0u32;
            while let Some(payload) = items.next().await {
                let id = count + 1;
                let formatted =
                    example::async_pipeline::enricher::format_item(id, payload).await;
                let remaining = out_tx.write_all(vec![formatted]).await;
                if !remaining.is_empty() {
                    break;
                }
                count += 1;
            }
            drop(out_tx);
            let _ = count_tx.write(count).await;
        });

        (out_rx, count_rx)
    }

    async fn sum_stream(mut values: StreamReader<u32>) -> u32 {
        let mut sum = 0u32;
        while let Some(value) = values.next().await {
            sum = sum.saturating_add(value);
        }
        sum
    }
}

export!(AsyncPipeline);
