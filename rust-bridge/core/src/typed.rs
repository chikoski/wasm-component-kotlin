//! What the generated typed bridges call (ADR-0016): reading and writing the binary format
//! (ADR-0015) into Rust values, and the resource rules shared with the generic path.

pub use crate::wire::{
    read_borrow_guest, read_borrow_host, read_discriminant, read_own_guest, read_own_host, write_discriminant, write_resource,
    Reader,
};
pub use crate::{call_failed, call_host, ImportKey, StoreData, TypedComponent, TypedExport};
pub use wasmtime::component::{ComponentType, Func, Lift, LinkerInstance, Lower, ResourceAny, TypedFunc};
pub use wasmtime::error::Context;
pub use wasmtime::{AsContextMut, Result, Store, StoreContextMut};

use wasmtime::{bail, format_err as anyhow};

/// The store as the generated code sees it while reading or writing values.
pub type Cx<'a> = StoreContextMut<'a, StoreData>;

/// A fixed-size number: written as its little-endian bytes, and a `list` of them as the bytes of
/// each element in order.
pub trait Prim: Copy {
    const SIZE: usize;
    fn from_le(b: &[u8]) -> Self;
    fn put_le(self, w: &mut Vec<u8>);
}

macro_rules! prim {
    ($($t:ty),*) => {$(
        impl Prim for $t {
            const SIZE: usize = std::mem::size_of::<$t>();
            #[inline]
            fn from_le(b: &[u8]) -> Self {
                <$t>::from_le_bytes(b.try_into().unwrap())
            }
            #[inline]
            fn put_le(self, w: &mut Vec<u8>) {
                w.extend_from_slice(&self.to_le_bytes());
            }
        }
    )*};
}

prim!(u8, i8, u16, i16, u32, i32, u64, i64, f32, f64);

#[inline]
pub fn read_prim<T: Prim>(r: &mut Reader<'_>) -> Result<T> {
    Ok(T::from_le(r.take(T::SIZE)?))
}

#[inline]
pub fn write_prim<T: Prim>(w: &mut Vec<u8>, v: T) {
    v.put_le(w)
}

pub fn read_bool(r: &mut Reader<'_>) -> Result<bool> {
    r.tag("bool")
}

pub fn write_bool(w: &mut Vec<u8>, v: bool) {
    w.push(v as u8)
}

pub fn read_char(r: &mut Reader<'_>) -> Result<char> {
    let v = r.u32()?;
    char::from_u32(v).ok_or_else(|| anyhow!("U+{:X} is not a Unicode scalar value", v))
}

pub fn write_char(w: &mut Vec<u8>, v: char) {
    w.extend_from_slice(&(v as u32).to_le_bytes())
}

/// A list's element count.
pub fn read_len(r: &mut Reader<'_>) -> Result<usize> {
    Ok(r.u32()? as usize)
}

pub fn write_len(w: &mut Vec<u8>, n: usize) -> Result<()> {
    let Ok(n) = u32::try_from(n) else { bail!("List of {} elements is too long", n) };
    w.extend_from_slice(&n.to_le_bytes());
    Ok(())
}

/// The `option` / `result` tag.
pub fn read_tag(r: &mut Reader<'_>, what: &str) -> Result<bool> {
    r.tag(what)
}

pub fn read_string(r: &mut Reader<'_>) -> Result<String> {
    let n = read_len(r)?;
    let bytes = r.take(n)?;
    String::from_utf8(bytes.to_vec()).map_err(|_| anyhow!("String is not valid UTF-8"))
}

pub fn write_string(w: &mut Vec<u8>, v: &str) -> Result<()> {
    write_len(w, v.len())?;
    w.extend_from_slice(v.as_bytes());
    Ok(())
}

/// `list<u8>`: one copy.
pub fn read_bytes(r: &mut Reader<'_>) -> Result<Vec<u8>> {
    let n = read_len(r)?;
    Ok(r.take(n)?.to_vec())
}

pub fn write_bytes(w: &mut Vec<u8>, v: &[u8]) -> Result<()> {
    write_len(w, v.len())?;
    w.extend_from_slice(v);
    Ok(())
}

/// A `list` of fixed-size numbers.
pub fn read_prim_list<T: Prim>(r: &mut Reader<'_>) -> Result<Vec<T>> {
    let n = read_len(r)?;
    let Some(size) = n.checked_mul(T::SIZE) else { bail!("List of {} elements is too long", n) };
    Ok(r.take(size)?.chunks_exact(T::SIZE).map(T::from_le).collect())
}

pub fn write_prim_list<T: Prim>(w: &mut Vec<u8>, v: &[T]) -> Result<()> {
    write_len(w, v.len())?;
    w.reserve(v.len() * T::SIZE);
    for x in v {
        x.put_le(w);
    }
    Ok(())
}

/// The host type payload of the imported resource `key` (`<interface>#<resource>` or `<resource>`).
pub fn host_payload(cx: &Cx<'_>, key: &str) -> Result<u32> {
    cx.data()
        .host_payloads
        .get(key)
        .copied()
        .ok_or_else(|| anyhow!("The component does not import the resource '{}'", key))
}

/// A `flags` value as `u32` words (bit `i` is flag `i`), read in the Canonical ABI's widths.
pub fn read_flag_words<const N: usize>(r: &mut Reader<'_>, count: usize) -> Result<[u32; N]> {
    let mut words = [0u32; N];
    match count {
        0 => {}
        1..=8 => words[0] = r.u8()? as u32,
        9..=16 => words[0] = r.u16()? as u32,
        _ => {
            for word in words.iter_mut() {
                *word = r.u32()?;
            }
        }
    }
    Ok(words)
}

pub fn write_flag_words(w: &mut Vec<u8>, count: usize, words: &[u32]) {
    match count {
        0 => {}
        1..=8 => w.push(words[0] as u8),
        9..=16 => w.extend_from_slice(&(words[0] as u16).to_le_bytes()),
        _ => words.iter().for_each(|word| w.extend_from_slice(&word.to_le_bytes())),
    }
}

/// The error for a discriminant past the type's cases (the host broke the format).
pub fn bad_discriminant(what: &str, d: usize, count: usize) -> wasmtime::Error {
    anyhow!("{} discriminant {} out of range for {} cases", what, d, count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flag_words_roundtrip_across_canonical_widths() {
        let mut empty = Vec::new();
        write_flag_words(&mut empty, 0, &[0xFF]);
        assert!(empty.is_empty());
        let mut r_empty = Reader::new(&empty);
        assert_eq!(read_flag_words::<1>(&mut r_empty, 0).unwrap(), [0]);
        r_empty.finish().unwrap();

        let mut byte_buf = Vec::new();
        write_flag_words(&mut byte_buf, 8, &[0xAB]);
        assert_eq!(byte_buf, [0xAB]);
        let mut r_byte = Reader::new(&byte_buf);
        assert_eq!(read_flag_words::<1>(&mut r_byte, 8).unwrap(), [0xAB]);
        r_byte.finish().unwrap();

        let mut short_buf = Vec::new();
        write_flag_words(&mut short_buf, 16, &[0xCAFE]);
        assert_eq!(short_buf, 0xCAFEu16.to_le_bytes());
        let mut r_short = Reader::new(&short_buf);
        assert_eq!(read_flag_words::<1>(&mut r_short, 16).unwrap(), [0xCAFE]);
        r_short.finish().unwrap();

        let words_in = [0xDEAD_BEEFu32, 0x0102_0304u32];
        let mut wide_buf = Vec::new();
        write_flag_words(&mut wide_buf, 33, &words_in);
        assert_eq!(wide_buf.len(), 8);
        let mut r_wide = Reader::new(&wide_buf);
        assert_eq!(read_flag_words::<2>(&mut r_wide, 33).unwrap(), words_in);
        r_wide.finish().unwrap();

        assert!(read_flag_words::<1>(&mut Reader::new(&[]), 8).is_err());
        assert!(read_flag_words::<1>(&mut Reader::new(&[1]), 9).is_err());
        assert!(read_flag_words::<2>(&mut Reader::new(&[0u8; 7]), 33).is_err());
    }
}
