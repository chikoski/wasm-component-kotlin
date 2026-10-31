wit_bindgen::generate!({ path: "host-res.wit", world: "host-res" });

use example::host_res::store;
use exports::example::host_res::guest_api::{Guest as GuestApi, GuestCounter};
use std::cell::Cell;

struct Fixture;

impl Guest for Fixture {
    fn make_and_measure(init: Vec<u8>, extra: Vec<u8>) -> u32 {
        let b = Blob::new(&init);
        b.append(&extra);
        b.size()
    }
    fn keep(init: Vec<u8>) -> Blob { Blob::new(&init) }
    fn measure(b: &Blob) -> u32 { b.size() }
    fn consume(b: Blob) -> u32 { b.size() }
    fn merge_new(a: Vec<u8>, b: Vec<u8>) -> Vec<u8> {
        let a = Blob::new(&a);
        let merged = Blob::merge(&a, Blob::new(&b));
        merged.read()
    }
    fn lookup(name: String) -> Option<Blob> { store::find(&name) }
    fn echo_blob(b: Blob) -> Result<Blob, String> {
        if b.size() == 0 { Err("empty".to_string()) } else { Ok(b) }
    }
    fn ticket_id(n: u32) -> u32 { Ticket::new(n).id() }
}

struct Counter(Cell<i32>);

impl GuestApi for Fixture {
    type Counter = Counter;
}

impl GuestCounter for Counter {
    fn new(v: i32) -> Self { Counter(Cell::new(v)) }
    fn poke(&self) {
        self.0.set(self.0.get() + 1);
        store::log("poke");
    }
    fn value(&self) -> i32 { self.0.get() }
}

export!(Fixture);
