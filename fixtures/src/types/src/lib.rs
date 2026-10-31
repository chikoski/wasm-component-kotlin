wit_bindgen::generate!({ path: "types.wit", world: "types-world" });

struct Fixture;

impl Guest for Fixture {
    fn echo_name(v: Option<String>) -> Option<String> { v }
    fn maybe_len(v: Option<String>) -> Option<u32> { v.map(|s| s.chars().count() as u32) }
    fn echo_bytes(v: Vec<u8>) -> Vec<u8> { v }
    fn reverse_bytes(mut v: Vec<u8>) -> Vec<u8> { v.reverse(); v }
    fn echo_char(c: char) -> char { c }
    fn next_char(c: char) -> char { char::from_u32(c as u32 + 1).unwrap_or(c) }
    fn echo_blob(b: Blob) -> Blob { b }
    fn echo_packet(p: Packet) -> Packet { p }
    fn compact(v: Vec<Option<i32>>) -> Vec<i32> { v.into_iter().flatten().collect() }
    fn echo_optional_list(v: Vec<Option<i32>>) -> Vec<Option<i32>> { v }
    fn nested(v: Option<Option<String>>) -> Option<Option<String>> { v }
}

export!(Fixture);
