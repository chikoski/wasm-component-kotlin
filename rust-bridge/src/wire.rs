//! The binary value format shared with the generated Kotlin bindings and the Chicory backend
//! (ADR-0015): tagless, little endian, values in declaration order without padding.
//!
//! * `bool`, `u8`, `s8`: 1 byte; wider integers and floats: little endian; `char`: `u32`
//! * `string`: `u32` byte length + UTF-8; `list<T>`: `u32` count + the elements
//! * `record` / `tuple`: the fields in order
//! * `variant` / `enum`: discriminant (`u8` / `u16` / `u32` by case count) + payload
//! * `option` / `result`: `u8` 0 / 1 + payload; `flags`: `u8` / `u16` / `u32` words
//! * `own` / `borrow`: `u32` (the rep of a host resource, or the bridge's handle of a guest resource)

use crate::{host_resource_payload, StoreData};
use wasmtime::component::{ResourceDynamic, Type, Val};
use wasmtime::{bail, format_err as anyhow, Result, StoreContextMut};

/// Reads values written by the Kotlin side.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if n > self.remaining() {
            bail!("WIT value ends early: need {} bytes, {} left", n, self.remaining());
        }
        let bytes = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(bytes)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into()?))
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into()?))
    }

    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.take(8)?.try_into()?))
    }

    fn tag(&mut self, what: &str) -> Result<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            other => bail!("Invalid {} tag {}", what, other),
        }
    }

    /// Fails unless every byte was read: the two sides disagree about the types.
    pub fn finish(&self) -> Result<()> {
        if self.remaining() != 0 {
            bail!("{} unread bytes after the WIT values", self.remaining());
        }
        Ok(())
    }
}

fn discriminant_width(count: usize) -> usize {
    if count <= 256 {
        1
    } else if count <= 65536 {
        2
    } else {
        4
    }
}

fn read_discriminant(r: &mut Reader<'_>, count: usize) -> Result<usize> {
    Ok(match discriminant_width(count) {
        1 => r.u8()? as usize,
        2 => r.u16()? as usize,
        _ => r.u32()? as usize,
    })
}

fn write_discriminant(w: &mut Vec<u8>, count: usize, index: usize) {
    match discriminant_width(count) {
        1 => w.push(index as u8),
        2 => w.extend_from_slice(&(index as u16).to_le_bytes()),
        _ => w.extend_from_slice(&(index as u32).to_le_bytes()),
    }
}

fn flag_words(count: usize) -> usize {
    count.div_ceil(32)
}

/// Reads a value of type `ty` (host values: errors are the host's, reported as such).
pub fn read_val(r: &mut Reader<'_>, ty: &Type, store: &mut StoreContextMut<'_, StoreData>) -> Result<Val> {
    Ok(match ty {
        Type::Bool => Val::Bool(r.tag("bool")?),
        Type::S8 => Val::S8(r.u8()? as i8),
        Type::U8 => Val::U8(r.u8()?),
        Type::S16 => Val::S16(r.u16()? as i16),
        Type::U16 => Val::U16(r.u16()?),
        Type::S32 => Val::S32(r.u32()? as i32),
        Type::U32 => Val::U32(r.u32()?),
        Type::S64 => Val::S64(r.u64()? as i64),
        Type::U64 => Val::U64(r.u64()?),
        Type::Float32 => Val::Float32(f32::from_bits(r.u32()?)),
        Type::Float64 => Val::Float64(f64::from_bits(r.u64()?)),
        Type::Char => {
            let v = r.u32()?;
            Val::Char(char::from_u32(v).ok_or_else(|| anyhow!("U+{:X} is not a Unicode scalar value", v))?)
        }
        Type::String => {
            let n = r.u32()? as usize;
            let bytes = r.take(n)?;
            Val::String(String::from_utf8(bytes.to_vec()).map_err(|_| anyhow!("String is not valid UTF-8"))?)
        }
        Type::List(list_ty) => {
            let n = r.u32()? as usize;
            let elem_ty = list_ty.ty();
            if matches!(elem_ty, Type::U8) {
                Val::List(r.take(n)?.iter().map(|b| Val::U8(*b)).collect())
            } else {
                // Every element takes at least one byte, except empty records or tuples.
                let mut vals = Vec::with_capacity(n.min(r.remaining()));
                for _ in 0..n {
                    vals.push(read_val(r, &elem_ty, store)?);
                }
                Val::List(vals)
            }
        }
        Type::Record(rec_ty) => {
            let mut fields = Vec::new();
            for field in rec_ty.fields() {
                fields.push((field.name.to_string(), read_val(r, &field.ty, store)?));
            }
            Val::Record(fields)
        }
        Type::Tuple(tup_ty) => {
            let mut vals = Vec::new();
            for elem_ty in tup_ty.types() {
                vals.push(read_val(r, &elem_ty, store)?);
            }
            Val::Tuple(vals)
        }
        Type::Variant(var_ty) => {
            let cases: Vec<_> = var_ty.cases().collect();
            let index = read_discriminant(r, cases.len())?;
            let case = cases.get(index).ok_or_else(|| anyhow!("Variant discriminant {} out of range for {} cases", index, cases.len()))?;
            let payload = match &case.ty {
                Some(payload_ty) => Some(Box::new(read_val(r, payload_ty, store)?)),
                None => None,
            };
            Val::Variant(case.name.to_string(), payload)
        }
        Type::Enum(enum_ty) => {
            let names: Vec<_> = enum_ty.names().collect();
            let index = read_discriminant(r, names.len())?;
            let name = names.get(index).ok_or_else(|| anyhow!("Enum discriminant {} out of range for {} cases", index, names.len()))?;
            Val::Enum(name.to_string())
        }
        Type::Option(opt_ty) => {
            if r.tag("option")? {
                Val::Option(Some(Box::new(read_val(r, &opt_ty.ty(), store)?)))
            } else {
                Val::Option(None)
            }
        }
        Type::Result(res_ty) => {
            if r.tag("result")? {
                let err = match res_ty.err() {
                    Some(err_ty) => Some(Box::new(read_val(r, &err_ty, store)?)),
                    None => None,
                };
                Val::Result(Err(err))
            } else {
                let ok = match res_ty.ok() {
                    Some(ok_ty) => Some(Box::new(read_val(r, &ok_ty, store)?)),
                    None => None,
                };
                Val::Result(Ok(ok))
            }
        }
        Type::Flags(flags_ty) => {
            let names: Vec<_> = flags_ty.names().collect();
            let bits: u64 = match names.len() {
                0 => 0,
                1..=8 => r.u8()? as u64,
                9..=16 => r.u16()? as u64,
                n => {
                    let mut bits = 0u64;
                    for word in 0..flag_words(n) {
                        let v = r.u32()? as u64;
                        if word < 2 {
                            bits |= v << (32 * word);
                        }
                    }
                    bits
                }
            };
            Val::Flags(
                names
                    .iter()
                    .enumerate()
                    .filter(|(i, _)| *i < 64 && bits & (1u64 << i) != 0)
                    .map(|(_, n)| n.to_string())
                    .collect(),
            )
        }
        Type::Own(rt) if host_resource_payload(rt, store).is_some() => {
            // A host resource, identified by the rep the JVM side assigned (ADR-0008).
            let payload = host_resource_payload(rt, store).unwrap_or_default();
            let rep = r.u32()?;
            Val::Resource(ResourceDynamic::new_own(rep, payload).try_into_resource_any(&mut *store)?)
        }
        Type::Borrow(rt) if host_resource_payload(rt, store).is_some() => {
            // Wasmtime (still in 48) cannot lower a host-made borrow outside a host call, so lend an
            // owned handle instead and release it once the call returns (see session_invoke_internal).
            let payload = host_resource_payload(rt, store).unwrap_or_default();
            let rep = r.u32()?;
            let any = ResourceDynamic::new_own(rep, payload).try_into_resource_any(&mut *store)?;
            store.data_mut().lent_host_resources.push(any);
            Val::Resource(any)
        }
        Type::Own(_) => {
            let handle = r.u32()? as i32;
            // The spec's lift_own: trap_if(h.num_lends != 0). Checked here, before Wasmtime sees the
            // call: Wasmtime 48 refuses it too, but leaves the handle's lend count raised, so the
            // host could never drop it afterwards. Same check and message as the Chicory backend.
            if store.data().borrowed_handles.contains(&handle) {
                bail!("Cannot move resource handle {} while it is borrowed by the same call", handle);
            }
            let res = store
                .data_mut()
                .resources
                .remove(&handle)
                .ok_or_else(|| anyhow!("Resource handle {} not found or already consumed", handle))?;
            store.data_mut().moved_handles.push((handle, res));
            Val::Resource(res)
        }
        Type::Borrow(_) => {
            let handle = r.u32()? as i32;
            store.data_mut().borrowed_handles.push(handle);
            let res = store
                .data()
                .resources
                .get(&handle)
                .copied()
                .ok_or_else(|| anyhow!("Resource handle {} not found", handle))?;
            Val::Resource(res)
        }
        // async types (#16) are not supported by the Wasmtime bridge; it stays the reference for the
        // synchronous features (ADR-0005).
        other => bail!("WIT type {:?} is not supported by the Wasmtime bridge", other),
    })
}

/// Writes `val` (of type `ty`, as Wasmtime lifted it).
pub fn write_val(w: &mut Vec<u8>, val: &Val, ty: &Type, store: &mut StoreContextMut<'_, StoreData>) -> Result<()> {
    match (val, ty) {
        (Val::Bool(b), _) => w.push(*b as u8),
        (Val::S8(v), _) => w.push(*v as u8),
        (Val::U8(v), _) => w.push(*v),
        (Val::S16(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::U16(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::S32(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::U32(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::S64(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::U64(v), _) => w.extend_from_slice(&v.to_le_bytes()),
        (Val::Float32(v), _) => w.extend_from_slice(&v.to_bits().to_le_bytes()),
        (Val::Float64(v), _) => w.extend_from_slice(&v.to_bits().to_le_bytes()),
        (Val::Char(c), _) => w.extend_from_slice(&(*c as u32).to_le_bytes()),
        (Val::String(s), _) => {
            w.extend_from_slice(&(s.len() as u32).to_le_bytes());
            w.extend_from_slice(s.as_bytes());
        }
        (Val::List(items), Type::List(list_ty)) => {
            w.extend_from_slice(&(items.len() as u32).to_le_bytes());
            let elem_ty = list_ty.ty();
            for item in items {
                write_val(w, item, &elem_ty, store)?;
            }
        }
        (Val::Record(fields), Type::Record(rec_ty)) => {
            for ((_, fval), field) in fields.iter().zip(rec_ty.fields()) {
                write_val(w, fval, &field.ty, store)?;
            }
        }
        (Val::Tuple(items), Type::Tuple(tup_ty)) => {
            for (item, elem_ty) in items.iter().zip(tup_ty.types()) {
                write_val(w, item, &elem_ty, store)?;
            }
        }
        (Val::Variant(name, payload), Type::Variant(var_ty)) => {
            let cases: Vec<_> = var_ty.cases().collect();
            let index = cases.iter().position(|c| c.name == name).ok_or_else(|| anyhow!("Unknown variant case {}", name))?;
            write_discriminant(w, cases.len(), index);
            if let (Some(pval), Some(pty)) = (payload, &cases[index].ty) {
                write_val(w, pval, pty, store)?;
            }
        }
        (Val::Enum(name), Type::Enum(enum_ty)) => {
            let names: Vec<_> = enum_ty.names().collect();
            let index = names.iter().position(|n| n == name).ok_or_else(|| anyhow!("Unknown enum case {}", name))?;
            write_discriminant(w, names.len(), index);
        }
        (Val::Option(opt), Type::Option(opt_ty)) => match opt {
            Some(inner) => {
                w.push(1);
                write_val(w, inner, &opt_ty.ty(), store)?;
            }
            None => w.push(0),
        },
        (Val::Result(res), Type::Result(res_ty)) => match res {
            Ok(ok) => {
                w.push(0);
                if let (Some(inner), Some(ok_ty)) = (ok, res_ty.ok()) {
                    write_val(w, inner, &ok_ty, store)?;
                }
            }
            Err(err) => {
                w.push(1);
                if let (Some(inner), Some(err_ty)) = (err, res_ty.err()) {
                    write_val(w, inner, &err_ty, store)?;
                }
            }
        },
        (Val::Flags(active), Type::Flags(flags_ty)) => {
            let names: Vec<_> = flags_ty.names().collect();
            let mut words = vec![0u32; flag_words(names.len())];
            for (i, name) in names.iter().enumerate() {
                if active.iter().any(|a| a == name) {
                    words[i / 32] |= 1 << (i % 32);
                }
            }
            match names.len() {
                0 => {}
                1..=8 => w.push(words[0] as u8),
                9..=16 => w.extend_from_slice(&(words[0] as u16).to_le_bytes()),
                _ => words.iter().for_each(|word| w.extend_from_slice(&word.to_le_bytes())),
            }
        }
        (Val::Resource(res_any), _) if host_resource_payload(&res_any.ty(), store).is_some() => {
            // A host resource coming back from the guest: its rep.
            let rep = ResourceDynamic::try_from_resource_any(*res_any, &mut *store)?.rep();
            w.extend_from_slice(&rep.to_le_bytes());
        }
        (Val::Resource(res_any), _) => {
            let data = store.data_mut();
            let handle = data.next_handle;
            data.next_handle += 1;
            data.resources.insert(handle, *res_any);
            w.extend_from_slice(&handle.to_le_bytes());
        }
        _ => bail!("Type/Val mismatch when writing a WIT value: {:?}", val),
    }
    Ok(())
}
