wit_bindgen::generate!({ path: "heavy.wit", world: "heavy" });

struct Fixture;

impl Guest for Fixture {
    fn regex_count(pattern: String, text: String) -> Result<u32, String> {
        let re = regex::Regex::new(&pattern).map_err(|e| e.to_string())?;
        Ok(re.find_iter(&text).count() as u32)
    }

    fn json_roundtrip(json: String) -> Result<String, String> {
        let v: serde_json::Value = serde_json::from_str(&json).map_err(|e| e.to_string())?;
        serde_json::to_string(&v).map_err(|e| e.to_string())
    }

    fn lcg_sum(seed: u32, n: u32) -> u64 {
        let mut x = seed;
        let mut sum = 0u64;
        for _ in 0..n {
            x = x.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            sum = sum.wrapping_add((x >> 8) as u64);
        }
        sum
    }

    fn sort_bytes(mut data: Vec<u8>) -> Vec<u8> {
        data.sort_unstable();
        data
    }
}

export!(Fixture);
