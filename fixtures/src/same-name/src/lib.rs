wit_bindgen::generate!({ path: "same-name.wit", world: "same-name", generate_all });

use example::fs::types as fs;
use example::http::types as http;
use exports::example::fs::types::{ErrorCode as FsErrorCode, Guest as FsTypes};
use exports::example::http::types::{ErrorCode, Guest as HttpTypes, GuestTally};
use std::cell::Cell;

struct Fixture;

impl Guest for Fixture {
    fn run(code: u32) -> String {
        let h = http::describe(http::ErrorCode { code });
        let f = fs::describe(&fs::ErrorCode::Missing(format!("f{code}")));
        let d = fs::describe(&fs::ErrorCode::Denied);
        let t = http::Tally::new(code).get();
        format!("{h}|{f}|{d}|tally={t}")
    }
}

/// The export copy of `example:http/types`, implemented by the guest.
struct Tally(Cell<u32>);

impl HttpTypes for Fixture {
    type Tally = Tally;
    fn describe(e: ErrorCode) -> String {
        format!("guest-http:{}", e.code)
    }
}

/// The export copy of `example:fs/types`.
impl FsTypes for Fixture {
    fn describe(e: FsErrorCode) -> String {
        match e {
            FsErrorCode::Missing(path) => format!("guest-fs:missing:{path}"),
            FsErrorCode::Denied => "guest-fs:denied".to_string(),
        }
    }
}

impl GuestTally for Tally {
    fn new(start: u32) -> Self {
        Tally(Cell::new(start))
    }
    fn get(&self) -> u32 {
        self.0.set(self.0.get() + 1);
        self.0.get()
    }
}

export!(Fixture);
