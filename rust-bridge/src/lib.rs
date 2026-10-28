use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;
use serde_json::{json, Value as JsonValue};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;
use wasmtime::component::{Component, Instance, Linker, ResourceAny, Type, Val};
use wasmtime::{Config, Engine, Precompiled, Store};

/// Host callback function pointer type:
/// Receives `(user_data, import_name_json_or_str, args_json_cstr)` and returns a newly allocated C string JSON response
/// (freed by Rust via `wasm2kt_free_host_string` or written into a callback buffer).
pub type HostCallbackFn = extern "C" fn(
    import_name: *const c_char,
    args_json: *const c_char,
    out_buf: *mut c_char,
    out_buf_cap: usize,
) -> i32;

pub struct StoreData {
    pub host_callback: Option<HostCallbackFn>,
    pub resources: HashMap<i32, ResourceAny>,
    pub next_handle: i32,
}

pub struct BridgeSession {
    pub engine: Engine,
    pub component: Component,
    pub store: Store<StoreData>,
    pub instance: Instance,
}

#[derive(Serialize)]
struct BridgeResponse {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    value: Option<JsonValue>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

fn to_cstring_json(resp: BridgeResponse) -> *mut c_char {
    let s = serde_json::to_string(&resp).unwrap_or_else(|e| {
        format!(r#"{{"ok":false,"error":"Failed to serialize response: {}"}}"#, e)
    });
    CString::new(s).unwrap_or_default().into_raw()
}

fn ok_response(val: JsonValue) -> *mut c_char {
    to_cstring_json(BridgeResponse {
        ok: true,
        value: Some(val),
        error: None,
    })
}

fn err_response(err: impl ToString) -> *mut c_char {
    to_cstring_json(BridgeResponse {
        ok: false,
        value: None,
        error: Some(err.to_string()),
    })
}

/// Convert JSON representation + expected `wasmtime::component::Type` into `wasmtime::component::Val`.
fn json_to_val(
    j: &JsonValue,
    ty: &Type,
    resources: &mut HashMap<i32, ResourceAny>,
) -> Result<Val> {
    match ty {
        Type::Bool => Ok(Val::Bool(
            j.as_bool().ok_or_else(|| anyhow!("Expected bool, got {}", j))?,
        )),
        Type::S8 => Ok(Val::S8(
            j.as_i64().ok_or_else(|| anyhow!("Expected s8, got {}", j))? as i8,
        )),
        Type::U8 => Ok(Val::U8(
            j.as_u64()
                .map(|v| v as u8)
                .or_else(|| j.as_i64().map(|v| v as u8))
                .ok_or_else(|| anyhow!("Expected u8, got {}", j))?,
        )),
        Type::S16 => Ok(Val::S16(
            j.as_i64().ok_or_else(|| anyhow!("Expected s16, got {}", j))? as i16,
        )),
        Type::U16 => Ok(Val::U16(
            j.as_u64()
                .map(|v| v as u16)
                .or_else(|| j.as_i64().map(|v| v as u16))
                .ok_or_else(|| anyhow!("Expected u16, got {}", j))?,
        )),
        Type::S32 => Ok(Val::S32(
            j.as_i64().ok_or_else(|| anyhow!("Expected s32, got {}", j))? as i32,
        )),
        Type::U32 => Ok(Val::U32(
            j.as_u64()
                .map(|v| v as u32)
                .or_else(|| j.as_i64().map(|v| v as u32))
                .ok_or_else(|| anyhow!("Expected u32, got {}", j))?,
        )),
        Type::S64 => Ok(Val::S64(
            j.as_i64().ok_or_else(|| anyhow!("Expected s64, got {}", j))?,
        )),
        Type::U64 => Ok(Val::U64(
            j.as_u64()
                .or_else(|| j.as_i64().map(|v| v as u64))
                .or_else(|| j.as_str()?.parse::<u64>().ok())
                .ok_or_else(|| anyhow!("Expected u64, got {}", j))?,
        )),
        Type::Float32 => Ok(Val::Float32(
            j.as_f64().ok_or_else(|| anyhow!("Expected f32, got {}", j))? as f32,
        )),
        Type::Float64 => Ok(Val::Float64(
            j.as_f64().ok_or_else(|| anyhow!("Expected f64, got {}", j))?,
        )),
        Type::Char => {
            let s = j.as_str().ok_or_else(|| anyhow!("Expected char string, got {}", j))?;
            let ch = s.chars().next().ok_or_else(|| anyhow!("Empty char string"))?;
            Ok(Val::Char(ch))
        }
        Type::String => Ok(Val::String(
            j.as_str()
                .ok_or_else(|| anyhow!("Expected string, got {}", j))?
                .to_string(),
        )),
        Type::List(list_ty) => {
            let arr = j.as_array().ok_or_else(|| anyhow!("Expected array for list, got {}", j))?;
            let elem_ty = list_ty.ty();
            let mut vals = Vec::with_capacity(arr.len());
            for item in arr {
                vals.push(json_to_val(item, &elem_ty, resources)?);
            }
            Ok(Val::List(vals))
        }
        Type::Record(rec_ty) => {
            let obj = j.as_object().ok_or_else(|| anyhow!("Expected object for record, got {}", j))?;
            let mut fields = Vec::new();
            for field in rec_ty.fields() {
                let fval = obj
                    .get(field.name)
                    .ok_or_else(|| anyhow!("Missing record field '{}'", field.name))?;
                fields.push((field.name.to_string(), json_to_val(fval, &field.ty, resources)?));
            }
            Ok(Val::Record(fields))
        }
        Type::Tuple(tup_ty) => {
            let arr = j.as_array().ok_or_else(|| anyhow!("Expected array for tuple, got {}", j))?;
            let mut vals = Vec::new();
            for (idx, elem_ty) in tup_ty.types().enumerate() {
                let item = arr
                    .get(idx)
                    .ok_or_else(|| anyhow!("Missing tuple element at {}", idx))?;
                vals.push(json_to_val(item, &elem_ty, resources)?);
            }
            Ok(Val::Tuple(vals))
        }
        Type::Variant(var_ty) => {
            let obj = j.as_object().ok_or_else(|| anyhow!("Expected object for variant, got {}", j))?;
            let tag = obj
                .get("case")
                .and_then(|v| v.as_str())
                .ok_or_else(|| anyhow!("Missing 'case' in variant JSON: {}", j))?;
            let case_def = var_ty
                .cases()
                .find(|c| c.name == tag)
                .ok_or_else(|| anyhow!("Unknown variant case '{}'", tag))?;
            let payload = match case_def.ty {
                Some(ref payload_ty) => {
                    let pval = obj.get("value").unwrap_or(&JsonValue::Null);
                    Some(Box::new(json_to_val(pval, payload_ty, resources)?))
                }
                None => None,
            };
            Ok(Val::Variant(tag.to_string(), payload))
        }
        Type::Enum(_) => {
            let s = j.as_str().ok_or_else(|| anyhow!("Expected string for enum, got {}", j))?;
            Ok(Val::Enum(s.to_string()))
        }
        Type::Option(opt_ty) => {
            if j.is_null() {
                return Ok(Val::Option(None));
            }
            if let Some(obj) = j.as_object() {
                if let Some(case_str) = obj.get("option").and_then(|v| v.as_str()) {
                    return match case_str {
                        "none" => Ok(Val::Option(None)),
                        "some" => {
                            let inner_json = obj.get("value").unwrap_or(&JsonValue::Null);
                            let inner_val = json_to_val(inner_json, &opt_ty.ty(), resources)?;
                            Ok(Val::Option(Some(Box::new(inner_val))))
                        }
                        other => bail!("Invalid option tag: {}", other),
                    };
                }
            }
            let inner_val = json_to_val(j, &opt_ty.ty(), resources)?;
            Ok(Val::Option(Some(Box::new(inner_val))))
        }
        Type::Result(res_ty) => {
            let obj = j.as_object().ok_or_else(|| anyhow!("Expected object for result, got {}", j))?;
            let status = obj
                .get("result")
                .and_then(|v| v.as_str())
                .ok_or_else(|| anyhow!("Missing 'result' tag in {}", j))?;
            match status {
                "ok" => {
                    let ok_val = match res_ty.ok() {
                        Some(ok_ty) => {
                            let v = obj.get("value").unwrap_or(&JsonValue::Null);
                            Some(Box::new(json_to_val(v, &ok_ty, resources)?))
                        }
                        None => None,
                    };
                    Ok(Val::Result(Ok(ok_val)))
                }
                "err" => {
                    let err_val = match res_ty.err() {
                        Some(err_ty) => {
                            let v = obj.get("error").unwrap_or(&JsonValue::Null);
                            Some(Box::new(json_to_val(v, &err_ty, resources)?))
                        }
                        None => None,
                    };
                    Ok(Val::Result(Err(err_val)))
                }
                other => bail!("Unknown result status '{}'", other),
            }
        }
        Type::Flags(flags_ty) => {
            if let Some(arr) = j.as_array() {
                let names: Result<Vec<String>> = arr
                    .iter()
                    .map(|v| {
                        v.as_str()
                            .map(|s| s.to_string())
                            .ok_or_else(|| anyhow!("Flag name must be string"))
                    })
                    .collect();
                Ok(Val::Flags(names?))
            } else if let Some(bits) = j.as_u64().or_else(|| j.as_i64().map(|v| v as u64)).or_else(|| j.as_str()?.parse::<u64>().ok()) {
                let mut active = Vec::new();
                for (idx, flag_name) in flags_ty.names().enumerate() {
                    if (bits & (1u64 << idx)) != 0 {
                        active.push(flag_name.to_string());
                    }
                }
                Ok(Val::Flags(active))
            } else {
                bail!("Expected array or u64 bitmask for flags, got {}", j)
            }
        }
        Type::Own(_) => {
            let handle = j
                .as_i64()
                .or_else(|| j.get("handle")?.as_i64())
                .ok_or_else(|| anyhow!("Expected integer handle for own<resource>, got {}", j))?
                as i32;
            let res = resources
                .remove(&handle)
                .ok_or_else(|| anyhow!("Resource handle {} not found or already consumed", handle))?;
            Ok(Val::Resource(res))
        }
        Type::Borrow(_) => {
            let handle = j
                .as_i64()
                .or_else(|| j.get("handle")?.as_i64())
                .ok_or_else(|| anyhow!("Expected integer handle for borrow<resource>, got {}", j))?
                as i32;
            let res = resources
                .get(&handle)
                .copied()
                .ok_or_else(|| anyhow!("Resource handle {} not found", handle))?;
            Ok(Val::Resource(res))
        }
    }
}

/// Convert `wasmtime::component::Val` back to JSON.
fn val_to_json(
    val: &Val,
    ty: &Type,
    resources: &mut HashMap<i32, ResourceAny>,
    next_handle: &mut i32,
) -> Result<JsonValue> {
    match (val, ty) {
        (Val::Bool(b), _) => Ok(json!(*b)),
        (Val::S8(v), _) => Ok(json!(*v)),
        (Val::U8(v), _) => Ok(json!(*v)),
        (Val::S16(v), _) => Ok(json!(*v)),
        (Val::U16(v), _) => Ok(json!(*v)),
        (Val::S32(v), _) => Ok(json!(*v)),
        (Val::U32(v), _) => Ok(json!(*v)),
        (Val::S64(v), _) => Ok(json!(*v)),
        (Val::U64(v), _) => Ok(json!(*v)),
        (Val::Float32(v), _) => Ok(json!(*v)),
        (Val::Float64(v), _) => Ok(json!(*v)),
        (Val::Char(c), _) => Ok(json!(c.to_string())),
        (Val::String(s), _) => Ok(json!(s)),
        (Val::List(items), Type::List(list_ty)) => {
            let elem_ty = list_ty.ty();
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                out.push(val_to_json(item, &elem_ty, resources, next_handle)?);
            }
            Ok(JsonValue::Array(out))
        }
        (Val::Record(fields), Type::Record(rec_ty)) => {
            let mut map = serde_json::Map::new();
            let field_types: HashMap<&str, Type> = rec_ty.fields().map(|f| (f.name, f.ty)).collect();
            for (name, fval) in fields {
                let fty = field_types
                    .get(name.as_str())
                    .ok_or_else(|| anyhow!("Unknown field {}", name))?;
                map.insert(name.clone(), val_to_json(fval, fty, resources, next_handle)?);
            }
            Ok(JsonValue::Object(map))
        }
        (Val::Tuple(items), Type::Tuple(tup_ty)) => {
            let mut out = Vec::with_capacity(items.len());
            for (item, elem_ty) in items.iter().zip(tup_ty.types()) {
                out.push(val_to_json(item, &elem_ty, resources, next_handle)?);
            }
            Ok(JsonValue::Array(out))
        }
        (Val::Variant(tag, payload), Type::Variant(var_ty)) => {
            let case_def = var_ty
                .cases()
                .find(|c| c.name == tag)
                .ok_or_else(|| anyhow!("Unknown variant case {}", tag))?;
            let payload_json = match (payload, case_def.ty) {
                (Some(pval), Some(pty)) => val_to_json(pval, &pty, resources, next_handle)?,
                _ => JsonValue::Null,
            };
            Ok(json!({
                "case": tag,
                "value": payload_json
            }))
        }
        (Val::Enum(name), _) => Ok(json!(name)),
        (Val::Option(opt), Type::Option(opt_ty)) => match opt {
            Some(inner) => {
                let inner_j = val_to_json(inner, &opt_ty.ty(), resources, next_handle)?;
                Ok(json!({
                    "option": "some",
                    "value": inner_j
                }))
            }
            None => Ok(json!({
                "option": "none"
            })),
        },
        (Val::Result(res), Type::Result(res_ty)) => match res {
            Ok(ok_opt) => {
                let v = match (ok_opt, res_ty.ok()) {
                    (Some(inner), Some(ok_ty)) => val_to_json(inner, &ok_ty, resources, next_handle)?,
                    _ => JsonValue::Null,
                };
                Ok(json!({
                    "result": "ok",
                    "value": v
                }))
            }
            Err(err_opt) => {
                let v = match (err_opt, res_ty.err()) {
                    (Some(inner), Some(err_ty)) => val_to_json(inner, &err_ty, resources, next_handle)?,
                    _ => JsonValue::Null,
                };
                Ok(json!({
                    "result": "err",
                    "error": v
                }))
            }
        },
        (Val::Flags(active_names), Type::Flags(flags_ty)) => {
            let mut mask: u64 = 0;
            for (idx, flag_name) in flags_ty.names().enumerate() {
                if active_names.iter().any(|n| n == flag_name) {
                    mask |= 1u64 << idx;
                }
            }
            Ok(json!(mask))
        }
        (Val::Resource(res_any), _) => {
            let h = *next_handle;
            *next_handle += 1;
            resources.insert(h, *res_any);
            Ok(json!(h))
        }
        _ => bail!("Type/Val mismatch when converting Val to JSON: {:?}", val),
    }
}

/// Register host import functions requested via JSON metadata:
/// `imports_spec_json` is a JSON array of import names (e.g. `["log", "env#get"]`).
fn register_host_imports(
    linker: &mut Linker<StoreData>,
    component: &Component,
    imports_list: &[String],
) -> Result<()> {
    let comp_ty = component.component_type();
    let engine = component.engine();

    // Auto-discover all imported functions and interfaces from the component type
    for (import_name, import_item) in comp_ty.imports(engine) {
        match import_item {
            wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) => {
                let name_owned = import_name.to_string();
                let result_types: Vec<Type> = func_ty.results().collect();
                linker
                    .root()
                    .func_new(import_name, move |mut ctx, params, results| {
                        invoke_host_callback(&mut ctx, &name_owned, params, results, &result_types)
                    })?;
            }
            wasmtime::component::types::ComponentItem::ComponentInstance(inst_ty) => {
                let mut inst = linker
                    .instance(import_name)
                    .with_context(|| format!("Failed to define linker instance '{}'", import_name))?;
                let short_iface = import_name
                    .split('/')
                    .nth(1)
                    .map(|s| s.split('@').next().unwrap_or(s))
                    .unwrap_or(import_name);
                for (export_fn_name, export_item) in inst_ty.exports(engine) {
                    if let wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) = export_item {
                        let callback_key = format!("{}#{}", short_iface, export_fn_name);
                        let result_types: Vec<Type> = func_ty.results().collect();
                        inst.func_new(export_fn_name, move |mut ctx, params, results| {
                            invoke_host_callback(&mut ctx, &callback_key, params, results, &result_types)
                        })?;
                    }
                }
            }
            _ => {}
        }
    }

    let _ = imports_list;
    Ok(())
}

fn invoke_host_callback(
    ctx: &mut wasmtime::StoreContextMut<'_, StoreData>,
    import_name: &str,
    params: &[Val],
    results: &mut [Val],
    result_types: &[Type],
) -> Result<()> {
    let cb = match ctx.data().host_callback {
        Some(f) => f,
        None => return Ok(()),
    };

    // Serialize primitive/simple Val params into JSON array for the host callback
    let mut json_params = Vec::with_capacity(params.len());
    for p in params {
        let j = match p {
            Val::Bool(b) => json!(*b),
            Val::S8(v) => json!(*v),
            Val::U8(v) => json!(*v),
            Val::S16(v) => json!(*v),
            Val::U16(v) => json!(*v),
            Val::S32(v) => json!(*v),
            Val::U32(v) => json!(*v),
            Val::S64(v) => json!(*v),
            Val::U64(v) => json!(*v),
            Val::Float32(v) => json!(*v),
            Val::Float64(v) => json!(*v),
            Val::Char(c) => json!(c.to_string()),
            Val::String(s) => json!(s),
            _ => json!(format!("{:?}", p)),
        };
        json_params.push(j);
    }

    let c_import = CString::new(import_name)?;
    let c_args = CString::new(serde_json::to_string(&json_params)?)?;
    let mut out_buf = vec![0u8; 65536];

    let status = cb(
        c_import.as_ptr(),
        c_args.as_ptr(),
        out_buf.as_mut_ptr() as *mut c_char,
        out_buf.len(),
    );
    if status != 0 {
        let err_str = unsafe { CStr::from_ptr(out_buf.as_ptr() as *const c_char) }
            .to_string_lossy()
            .into_owned();
        bail!("Host import '{}' failed: {}", import_name, err_str);
    }

    if !results.is_empty() {
        let resp_str = unsafe { CStr::from_ptr(out_buf.as_ptr() as *const c_char) }.to_str()?;
        if !resp_str.is_empty() {
            let resp_json: JsonValue = serde_json::from_str(resp_str)?;
            if results.len() == 1 && result_types.len() == 1 {
                results[0] = json_to_val(
                    &resp_json,
                    &result_types[0],
                    &mut ctx.data_mut().resources,
                )?;
            } else if let Some(arr) = resp_json.as_array() {
                for ((slot, r_ty), item_j) in results.iter_mut().zip(result_types.iter()).zip(arr.iter()) {
                    *slot = json_to_val(item_j, r_ty, &mut ctx.data_mut().resources)?;
                }
            }
        }
    }

    Ok(())
}

// ============================================================================
// Exported C ABI Functions for JVM Foreign Function & Memory (FFM) API
// ============================================================================

fn parse_imports_json(imports_json_ptr: *const c_char) -> Result<Vec<String>> {
    if imports_json_ptr.is_null() {
        return Ok(Vec::new());
    }
    let s = unsafe { CStr::from_ptr(imports_json_ptr) }.to_str()?;
    if s.trim().is_empty() {
        Ok(Vec::new())
    } else {
        serde_json::from_str(s).context("Invalid imports JSON array")
    }
}

fn create_session_from_component(
    engine: Engine,
    component: Component,
    imports_json_ptr: *const c_char,
    host_callback: Option<HostCallbackFn>,
) -> Result<BridgeSession> {
    let imports_list = parse_imports_json(imports_json_ptr)?;

    let mut linker = Linker::new(&engine);
    register_host_imports(&mut linker, &component, &imports_list)?;

    let mut store = Store::new(
        &engine,
        StoreData {
            host_callback,
            resources: HashMap::new(),
            next_handle: 1,
        },
    );

    let instance = linker
        .instantiate(&mut store, &component)
        .context("Failed to instantiate Wasm component")?;

    Ok(BridgeSession {
        engine,
        component,
        store,
        instance,
    })
}

fn write_err_buf(err: &anyhow::Error, err_buf: *mut c_char, err_buf_cap: usize) {
    if !err_buf.is_null() && err_buf_cap > 0 {
        let msg = format!("{:#}", err);
        let bytes = msg.as_bytes();
        let copy_len = bytes.len().min(err_buf_cap - 1);
        unsafe {
            ptr::copy_nonoverlapping(bytes.as_ptr(), err_buf as *mut u8, copy_len);
            *err_buf.add(copy_len) = 0;
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_open(
    wasm_path_ptr: *const c_char,
    imports_json_ptr: *const c_char,
    host_callback: Option<HostCallbackFn>,
    err_buf: *mut c_char,
    err_buf_cap: usize,
) -> *mut BridgeSession {
    let res = (|| -> Result<BridgeSession> {
        let wasm_path = unsafe { CStr::from_ptr(wasm_path_ptr) }
            .to_str()
            .context("Invalid UTF-8 in wasm_path")?;

        let mut config = Config::new();
        config.wasm_component_model(true);
        let engine = Engine::new(&config)?;
        let component = match engine.detect_precompiled_file(wasm_path) {
            Ok(Some(Precompiled::Component)) => unsafe { Component::deserialize_file(&engine, wasm_path) }
                .with_context(|| format!("Failed to deserialize precompiled .cwasm component from {}", wasm_path))?,
            _ => Component::from_file(&engine, wasm_path)
                .with_context(|| format!("Failed to load component from {}", wasm_path))?,
        };

        create_session_from_component(engine, component, imports_json_ptr, host_callback)
    })();

    match res {
        Ok(session) => Box::into_raw(Box::new(session)),
        Err(e) => {
            write_err_buf(&e, err_buf, err_buf_cap);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_open_bytes(
    wasm_bytes_ptr: *const u8,
    wasm_bytes_len: usize,
    imports_json_ptr: *const c_char,
    host_callback: Option<HostCallbackFn>,
    err_buf: *mut c_char,
    err_buf_cap: usize,
) -> *mut BridgeSession {
    let res = (|| -> Result<BridgeSession> {
        if wasm_bytes_ptr.is_null() {
            bail!("wasm_bytes_ptr is null");
        }
        let wasm_bytes = unsafe { std::slice::from_raw_parts(wasm_bytes_ptr, wasm_bytes_len) };

        let mut config = Config::new();
        config.wasm_component_model(true);
        let engine = Engine::new(&config)?;
        let component = match engine.detect_precompiled(wasm_bytes) {
            Some(Precompiled::Component) => unsafe { Component::deserialize(&engine, wasm_bytes) }
                .context("Failed to deserialize precompiled .cwasm component from bytes")?,
            _ => Component::from_binary(&engine, wasm_bytes)
                .context("Failed to load Wasm component from bytes")?,
        };

        create_session_from_component(engine, component, imports_json_ptr, host_callback)
    })();

    match res {
        Ok(session) => Box::into_raw(Box::new(session)),
        Err(e) => {
            write_err_buf(&e, err_buf, err_buf_cap);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_precompile_component(
    wasm_bytes_ptr: *const u8,
    wasm_bytes_len: usize,
    out_len_ptr: *mut usize,
    err_buf: *mut c_char,
    err_buf_cap: usize,
) -> *mut u8 {
    let res = (|| -> Result<Vec<u8>> {
        if wasm_bytes_ptr.is_null() {
            bail!("wasm_bytes_ptr is null");
        }
        if out_len_ptr.is_null() {
            bail!("out_len_ptr is null");
        }
        let wasm_bytes = unsafe { std::slice::from_raw_parts(wasm_bytes_ptr, wasm_bytes_len) };
        let mut config = Config::new();
        config.wasm_component_model(true);
        let engine = Engine::new(&config)?;
        engine
            .precompile_component(wasm_bytes)
            .context("Failed to precompile Wasm component into .cwasm")
    })();

    match res {
        Ok(cwasm_vec) => {
            let boxed_slice = cwasm_vec.into_boxed_slice();
            let len = boxed_slice.len();
            unsafe {
                *out_len_ptr = len;
            }
            Box::into_raw(boxed_slice) as *mut u8
        }
        Err(e) => {
            write_err_buf(&e, err_buf, err_buf_cap);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_free_bytes(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        unsafe {
            let slice_ptr = ptr::slice_from_raw_parts_mut(ptr, len);
            drop(Box::from_raw(slice_ptr));
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_invoke(
    session_ptr: *mut BridgeSession,
    func_spec_ptr: *const c_char,
    args_json_ptr: *const c_char,
) -> *mut c_char {
    if session_ptr.is_null() {
        return err_response("BridgeSession pointer is null");
    }
    let session = unsafe { &mut *session_ptr };

    let res = (|| -> Result<JsonValue> {
        let func_spec = unsafe { CStr::from_ptr(func_spec_ptr) }.to_str()?;
        let args_str = unsafe { CStr::from_ptr(args_json_ptr) }.to_str()?;
        let args_json: Vec<JsonValue> = serde_json::from_str(args_str)
            .with_context(|| format!("Failed to parse args JSON: {}", args_str))?;

        let func = resolve_exported_func(
            &session.component,
            &session.instance,
            &mut session.store,
            func_spec,
        )?;
        let param_types = func.params(&session.store);
        let result_types = func.results(&session.store);

        if args_json.len() != param_types.len() {
            bail!(
                "Function '{}' expected {} arguments, got {}",
                func_spec,
                param_types.len(),
                args_json.len()
            );
        }

        let mut wasm_params = Vec::with_capacity(param_types.len());
        for (arg_j, (_, p_ty)) in args_json.iter().zip(param_types.iter()) {
            let val = json_to_val(arg_j, p_ty, &mut session.store.data_mut().resources)?;
            wasm_params.push(val);
        }

        let mut wasm_results = vec![Val::Bool(false); result_types.len()];
        func.call(&mut session.store, &wasm_params, &mut wasm_results)
            .with_context(|| format!("Wasmtime call to '{}' failed", func_spec))?;
        func.post_return(&mut session.store)
            .with_context(|| format!("Wasmtime post_return for '{}' failed", func_spec))?;

        let data = session.store.data_mut();
        match result_types.len() {
            0 => Ok(JsonValue::Null),
            1 => val_to_json(
                &wasm_results[0],
                &result_types[0],
                &mut data.resources,
                &mut data.next_handle,
            ),
            _ => {
                let mut tuple_out = Vec::with_capacity(result_types.len());
                for (r_val, r_ty) in wasm_results.iter().zip(result_types.iter()) {
                    tuple_out.push(val_to_json(
                        r_val,
                        r_ty,
                        &mut data.resources,
                        &mut data.next_handle,
                    )?);
                }
                Ok(JsonValue::Array(tuple_out))
            }
        }
    })();

    match res {
        Ok(val) => ok_response(val),
        Err(e) => err_response(format!("{:#}", e)),
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_drop_resource(
    session_ptr: *mut BridgeSession,
    handle: i32,
) -> *mut c_char {
    if session_ptr.is_null() {
        return err_response("BridgeSession pointer is null");
    }
    let session = unsafe { &mut *session_ptr };

    let res = (|| -> Result<JsonValue> {
        let res_any = session
            .store
            .data_mut()
            .resources
            .remove(&handle)
            .ok_or_else(|| anyhow!("Resource handle {} is not active or already dropped", handle))?;
        res_any
            .resource_drop(&mut session.store)
            .with_context(|| format!("Failed to drop Wasmtime resource handle {}", handle))?;
        Ok(JsonValue::Null)
    })();

    match res {
        Ok(val) => ok_response(val),
        Err(e) => err_response(format!("{:#}", e)),
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_close(session_ptr: *mut BridgeSession) {
    if !session_ptr.is_null() {
        unsafe {
            drop(Box::from_raw(session_ptr));
        }
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

fn resolve_exported_func(
    component: &Component,
    instance: &Instance,
    store: &mut Store<StoreData>,
    func_spec: &str,
) -> Result<wasmtime::component::Func> {
    if let Some((iface_name, fn_name)) = func_spec.split_once('#') {
        if let Some(iface_export_idx) = instance.get_export(&mut *store, None, iface_name) {
            if let Some(func_export_idx) = instance.get_export(&mut *store, Some(&iface_export_idx), fn_name) {
                if let Some(f) = instance.get_func(&mut *store, func_export_idx) {
                    return Ok(f);
                }
            }
        }
        // Also try matching fully-qualified interface export names like `pkg:name/iface@ver`
        let comp_ty = component.component_type();
        let engine = component.engine();
        for (export_name, export_item) in comp_ty.exports(engine) {
            if let wasmtime::component::types::ComponentItem::ComponentInstance(_) = export_item {
                let matches_suffix = export_name == iface_name
                    || export_name
                        .split('/')
                        .nth(1)
                        .map(|s| s.split('@').next().unwrap_or(s) == iface_name)
                        .unwrap_or(false);
                if matches_suffix {
                    if let Some(iface_idx) = instance.get_export(&mut *store, None, export_name) {
                        if let Some(fn_idx) = instance.get_export(&mut *store, Some(&iface_idx), fn_name) {
                            if let Some(f) = instance.get_func(&mut *store, fn_idx) {
                                return Ok(f);
                            }
                        }
                    }
                }
            }
        }
        bail!(
            "Exported function '{}' not found in interface '{}'",
            fn_name,
            iface_name
        )
    } else {
        if let Some(f) = instance.get_func(&mut *store, func_spec) {
            return Ok(f);
        }
        // Search inside exported interfaces if a bare function or resource method name was given
        let comp_ty = component.component_type();
        let engine = component.engine();
        for (export_name, export_item) in comp_ty.exports(engine) {
            if let wasmtime::component::types::ComponentItem::ComponentInstance(_) = export_item {
                if let Some(iface_idx) = instance.get_export(&mut *store, None, export_name) {
                    if let Some(fn_idx) = instance.get_export(&mut *store, Some(&iface_idx), func_spec) {
                        if let Some(f) = instance.get_func(&mut *store, fn_idx) {
                            return Ok(f);
                        }
                    }
                }
                if let Some((_, short_iface)) = export_name.split_once('/') {
                    let short_iface_no_ver = short_iface.split('@').next().unwrap_or(short_iface);
                    if func_spec.starts_with(&format!("{}#", short_iface_no_ver)) {
                        let actual_fn = &func_spec[short_iface_no_ver.len() + 1..];
                        if let Some(iface_idx) = instance.get_export(&mut *store, None, export_name) {
                            if let Some(fn_idx) = instance.get_export(&mut *store, Some(&iface_idx), actual_fn) {
                                if let Some(f) = instance.get_func(&mut *store, fn_idx) {
                                    return Ok(f);
                                }
                            }
                        }
                    }
                }
            }
        }
        bail!("Exported function '{}' not found in component", func_spec)
    }
}
