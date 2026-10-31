use anyhow::{anyhow, bail, Context, Result};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde::Serialize;
use serde_json::{json, Value as JsonValue};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;
use wasmtime::component::{Component, Instance, Linker, Resource, ResourceAny, ResourceType, Type, Val};
use wasmtime::{AsContextMut, Config, Engine, Precompiled, Store, StoreContextMut};

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
    pub jni_callback: Option<(JavaVM, GlobalRef)>,
    pub resources: HashMap<i32, ResourceAny>,
    pub next_handle: i32,
    /// Resource types the component imports, as they appear in import signatures (uninstantiated).
    pub imported_resource_types: Vec<ResourceType>,
    /// Owned handles standing in for host borrows during the current call.
    pub lent_host_resources: Vec<ResourceAny>,
}

/// Whether `rt` is a host-defined resource: the host type itself (export signatures of an instance)
/// or an imported resource type as it appears in the component's import signatures.
fn is_host_resource(rt: &ResourceType, store: &StoreContextMut<'_, StoreData>) -> bool {
    *rt == host_resource_type() || store.data().imported_resource_types.contains(rt)
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

/// Marker type for every host-defined resource. The JVM side identifies the object by its rep;
/// all imported resource types share this host type (Wasmtime 29 has no dynamic host types).
struct HostRes;

fn host_resource_type() -> ResourceType {
    ResourceType::host::<HostRes>()
}

fn json_handle(j: &JsonValue, what: &str) -> Result<i64> {
    j.as_i64()
        .or_else(|| j.get("handle")?.as_i64())
        .ok_or_else(|| anyhow!("Expected integer handle for {}<resource>, got {}", what, j))
}

/// Convert JSON representation + expected `wasmtime::component::Type` into `wasmtime::component::Val`.
fn json_to_val(
    j: &JsonValue,
    ty: &Type,
    store: &mut StoreContextMut<'_, StoreData>,
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
                vals.push(json_to_val(item, &elem_ty, store)?);
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
                fields.push((field.name.to_string(), json_to_val(fval, &field.ty, store)?));
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
                vals.push(json_to_val(item, &elem_ty, store)?);
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
                    Some(Box::new(json_to_val(pval, payload_ty, store)?))
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
                            let inner_val = json_to_val(inner_json, &opt_ty.ty(), store)?;
                            Ok(Val::Option(Some(Box::new(inner_val))))
                        }
                        other => bail!("Invalid option tag: {}", other),
                    };
                }
            }
            let inner_val = json_to_val(j, &opt_ty.ty(), store)?;
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
                            Some(Box::new(json_to_val(v, &ok_ty, store)?))
                        }
                        None => None,
                    };
                    Ok(Val::Result(Ok(ok_val)))
                }
                "err" => {
                    let err_val = match res_ty.err() {
                        Some(err_ty) => {
                            let v = obj.get("error").unwrap_or(&JsonValue::Null);
                            Some(Box::new(json_to_val(v, &err_ty, store)?))
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
        Type::Own(rt) if is_host_resource(rt, store) => {
            // A host resource, identified by the rep the JVM side assigned.
            let rep = json_handle(j, "own")? as u32;
            Ok(Val::Resource(ResourceAny::try_from_resource(Resource::<HostRes>::new_own(rep), &mut *store)?))
        }
        Type::Borrow(rt) if is_host_resource(rt, store) => {
            // Wasmtime 29 cannot lower a host-made borrow outside a host call, so lend an owned
            // handle instead and release it once the call returns (see session_invoke_internal).
            let rep = json_handle(j, "borrow")? as u32;
            let any = ResourceAny::try_from_resource(Resource::<HostRes>::new_own(rep), &mut *store)?;
            store.data_mut().lent_host_resources.push(any);
            Ok(Val::Resource(any))
        }
        Type::Own(_) => {
            let handle = json_handle(j, "own")? as i32;
            let res = store
                .data_mut()
                .resources
                .remove(&handle)
                .ok_or_else(|| anyhow!("Resource handle {} not found or already consumed", handle))?;
            Ok(Val::Resource(res))
        }
        Type::Borrow(_) => {
            let handle = json_handle(j, "borrow")? as i32;
            let res = store
                .data()
                .resources
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
    store: &mut StoreContextMut<'_, StoreData>,
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
                out.push(val_to_json(item, &elem_ty, store)?);
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
                map.insert(name.clone(), val_to_json(fval, fty, store)?);
            }
            Ok(JsonValue::Object(map))
        }
        (Val::Tuple(items), Type::Tuple(tup_ty)) => {
            let mut out = Vec::with_capacity(items.len());
            for (item, elem_ty) in items.iter().zip(tup_ty.types()) {
                out.push(val_to_json(item, &elem_ty, store)?);
            }
            Ok(JsonValue::Array(out))
        }
        (Val::Variant(tag, payload), Type::Variant(var_ty)) => {
            let case_def = var_ty
                .cases()
                .find(|c| c.name == tag)
                .ok_or_else(|| anyhow!("Unknown variant case {}", tag))?;
            let payload_json = match (payload, case_def.ty) {
                (Some(pval), Some(pty)) => val_to_json(pval, &pty, store)?,
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
                let inner_j = val_to_json(inner, &opt_ty.ty(), store)?;
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
                    (Some(inner), Some(ok_ty)) => val_to_json(inner, &ok_ty, store)?,
                    _ => JsonValue::Null,
                };
                Ok(json!({
                    "result": "ok",
                    "value": v
                }))
            }
            Err(err_opt) => {
                let v = match (err_opt, res_ty.err()) {
                    (Some(inner), Some(err_ty)) => val_to_json(inner, &err_ty, store)?,
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
        (Val::Resource(res_any), _) if res_any.ty() == host_resource_type() => {
            // A host resource coming back from the guest: report its rep.
            let res: Resource<HostRes> = res_any.try_into_resource(&mut *store)?;
            Ok(json!(res.rep()))
        }
        (Val::Resource(res_any), _) => {
            let data = store.data_mut();
            let h = data.next_handle;
            data.next_handle += 1;
            data.resources.insert(h, *res_any);
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
) -> Result<Vec<ResourceType>> {
    let mut imported_resource_types = Vec::new();
    let comp_ty = component.component_type();
    let engine = component.engine();

    // Auto-discover all imported functions and interfaces from the component type
    for (import_name, import_item) in comp_ty.imports(engine) {
        match import_item {
            wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) => {
                let name_owned = import_name.to_string();
                let param_types: Vec<Type> = func_ty.params().map(|(_, ty)| ty).collect();
                let result_types: Vec<Type> = func_ty.results().collect();
                linker
                    .root()
                    .func_new(import_name, move |mut ctx, params, results| {
                        invoke_host_callback(
                            &mut ctx,
                            &name_owned,
                            params,
                            &param_types,
                            results,
                            &result_types,
                        )
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
                    if let wasmtime::component::types::ComponentItem::Resource(rt) = export_item {
                        imported_resource_types.push(rt);
                        let drop_key = format!("{}#[resource-drop]{}", short_iface, export_fn_name);
                        inst.resource(export_fn_name, host_resource_type(), host_resource_dtor(drop_key))?;
                        continue;
                    }
                    if let wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) = export_item {
                        let callback_key = format!("{}#{}", short_iface, export_fn_name);
                        let param_types: Vec<Type> = func_ty.params().map(|(_, ty)| ty).collect();
                        let result_types: Vec<Type> = func_ty.results().collect();
                        inst.func_new(export_fn_name, move |mut ctx, params, results| {
                            invoke_host_callback(
                                &mut ctx,
                                &callback_key,
                                params,
                                &param_types,
                                results,
                                &result_types,
                            )
                        })?;
                    }
                }
            }
            wasmtime::component::types::ComponentItem::Resource(rt) => {
                imported_resource_types.push(rt);
                let drop_key = format!("[resource-drop]{}", import_name);
                linker.root().resource(import_name, host_resource_type(), host_resource_dtor(drop_key))?;
            }
            _ => {}
        }
    }

    let _ = imports_list;
    Ok(imported_resource_types)
}

/// Called when the guest drops its last owning handle to a host resource: forwards
/// `[resource-drop]<name>` with the rep to the JVM host, which closes the object.
fn host_resource_dtor(
    key: String,
) -> impl Fn(StoreContextMut<'_, StoreData>, u32) -> Result<()> + Send + Sync + 'static {
    move |mut ctx, rep| invoke_host_callback(&mut ctx, &key, &[Val::U32(rep)], &[Type::U32], &mut [], &[])
}

fn extract_and_clear_jni_exception(env: &mut JNIEnv<'_>) -> String {
    if let Ok(throwable) = env.exception_occurred() {
        let _ = env.exception_clear();
        if !throwable.is_null() {
            if let Ok(msg_val) = env.call_method(&throwable, "toString", "()Ljava/lang/String;", &[]) {
                if let Ok(msg_obj) = msg_val.l() {
                    if !msg_obj.is_null() {
                        let j_str = JString::from(msg_obj);
                        if let Ok(rust_str) = env.get_string(&j_str) {
                            return rust_str.into();
                        };
                    }
                }
            }
        }
    } else {
        let _ = env.exception_clear();
    }
    "Unknown JVM exception".to_string()
}

fn invoke_host_callback(
    ctx: &mut wasmtime::StoreContextMut<'_, StoreData>,
    import_name: &str,
    params: &[Val],
    param_types: &[Type],
    results: &mut [Val],
    result_types: &[Type],
) -> Result<()> {
    if ctx.data().host_callback.is_none() && ctx.data().jni_callback.is_none() {
        return Ok(());
    }

    // Serialize all Val params (including records, variants, enums, flags, options, results, lists, tuples, and resources) into JSON array
    let mut json_params = Vec::with_capacity(params.len());
    {
        let mut sc = ctx.as_context_mut();
        for (p, p_ty) in params.iter().zip(param_types.iter()) {
            let j = val_to_json(p, p_ty, &mut sc)?;
            json_params.push(j);
        }
    }

    let args_json_str = serde_json::to_string(&json_params)?;

    let resp_str_opt: Option<String> = if let Some(cb) = ctx.data().host_callback {
        let c_import = CString::new(import_name)?;
        let c_args = CString::new(args_json_str)?;
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
        let s = unsafe { CStr::from_ptr(out_buf.as_ptr() as *const c_char) }
            .to_str()?
            .to_string();
        if s.is_empty() {
            None
        } else {
            Some(s)
        }
    } else if let Some((ref vm, ref callback_ref)) = ctx.data().jni_callback {
        let mut env = vm
            .get_env()
            .or_else(|_| vm.attach_current_thread_permanently())
            .context("Failed to acquire JNIEnv for host callback")?;
        env.with_local_frame(16, |env| -> Result<Option<String>> {
            let j_import = env.new_string(import_name)?;
            let j_args = env.new_string(&args_json_str)?;
            let j_import_obj: JObject = j_import.into();
            let j_args_obj: JObject = j_args.into();
            let call_res = env.call_method(
                callback_ref,
                "onHostCall",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&j_import_obj), JValue::Object(&j_args_obj)],
            );
            if env.exception_check().unwrap_or(false) {
                let exc_msg = extract_and_clear_jni_exception(env);
                bail!(
                    "Host import '{}' threw an exception in JVM/Android host: {}",
                    import_name,
                    exc_msg
                );
            }
            let ret_val = call_res.with_context(|| format!("JNI call_method onHostCall failed for '{}'", import_name))?;
            let ret_obj = ret_val.l()?;
            if ret_obj.is_null() {
                Ok(None)
            } else {
                let j_str = JString::from(ret_obj);
                let s: String = env.get_string(&j_str)?.into();
                if s.is_empty() {
                    Ok(None)
                } else {
                    Ok(Some(s))
                }
            }
        })?
    } else {
        None
    };

    if !results.is_empty() {
        // An empty response is JSON null (e.g. an option import returning none).
        {
            let resp_str = resp_str_opt.unwrap_or_else(|| "null".to_string());
            let resp_json: JsonValue = serde_json::from_str(&resp_str)?;
            if results.len() == 1 && result_types.len() == 1 {
                results[0] = json_to_val(&resp_json, &result_types[0], &mut ctx.as_context_mut())?;
            } else if let Some(arr) = resp_json.as_array() {
                for ((slot, r_ty), item_j) in results.iter_mut().zip(result_types.iter()).zip(arr.iter()) {
                    *slot = json_to_val(item_j, r_ty, &mut ctx.as_context_mut())?;
                }
            }
        }
    }

    Ok(())
}

// ============================================================================
// Shared Engine, Thread-Safe Session Registry & Exported C ABI / JNI Functions
// ============================================================================

static SHARED_ENGINE: std::sync::OnceLock<Engine> = std::sync::OnceLock::new();
static NEXT_SESSION_ID: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(1);
static SESSIONS: std::sync::OnceLock<std::sync::Mutex<HashMap<usize, std::sync::Arc<ManagedSession>>>> =
    std::sync::OnceLock::new();

thread_local! {
    static ACTIVE_SESSIONS: std::cell::RefCell<std::collections::HashSet<usize>> =
        std::cell::RefCell::new(std::collections::HashSet::new());
}

struct ManagedSession {
    closed: std::sync::atomic::AtomicBool,
    inner: std::sync::Mutex<BridgeSession>,
}

struct ActiveSessionGuard(usize);

impl Drop for ActiveSessionGuard {
    fn drop(&mut self) {
        ACTIVE_SESSIONS.with(|set| {
            set.borrow_mut().remove(&self.0);
        });
    }
}

fn sessions_map() -> &'static std::sync::Mutex<HashMap<usize, std::sync::Arc<ManagedSession>>> {
    SESSIONS.get_or_init(|| std::sync::Mutex::new(HashMap::new()))
}

fn register_session(session: BridgeSession) -> usize {
    let mut id = NEXT_SESSION_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    if id == 0 {
        id = NEXT_SESSION_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    let managed = std::sync::Arc::new(ManagedSession {
        closed: std::sync::atomic::AtomicBool::new(false),
        inner: std::sync::Mutex::new(session),
    });
    if let Ok(mut map) = sessions_map().lock() {
        map.insert(id, managed);
    }
    id
}

fn with_session_mut<T, F: FnOnce(&mut BridgeSession) -> Result<T>>(session_id: usize, f: F) -> Result<T> {
    if session_id == 0 {
        bail!("BridgeSession pointer is null");
    }
    let is_reentrant = ACTIVE_SESSIONS.with(|set| set.borrow().contains(&session_id));
    if is_reentrant {
        bail!("Reentrant operation on active WasmtimeSession is not permitted");
    }
    let managed = {
        let map = sessions_map()
            .lock()
            .map_err(|_| anyhow!("Session registry mutex poisoned"))?;
        map.get(&session_id)
            .cloned()
            .ok_or_else(|| anyhow!("WasmtimeSession is already closed or invalid"))?
    };
    let mut guard = managed
        .inner
        .lock()
        .map_err(|_| anyhow!("WasmtimeSession mutex poisoned"))?;
    if managed.closed.load(std::sync::atomic::Ordering::Acquire) {
        bail!("WasmtimeSession is already closed");
    }
    ACTIVE_SESSIONS.with(|set| {
        set.borrow_mut().insert(session_id);
    });
    let _active_guard = ActiveSessionGuard(session_id);
    let res = f(&mut guard);
    if managed.closed.load(std::sync::atomic::Ordering::Acquire) {
        bail!("WasmtimeSession was closed during host callback execution");
    }
    res
}

fn close_session_handle(session_id: usize) {
    if session_id == 0 {
        return;
    }
    let removed = sessions_map()
        .lock()
        .ok()
        .and_then(|mut map| map.remove(&session_id));
    if let Some(managed) = removed {
        managed.closed.store(true, std::sync::atomic::Ordering::Release);
        let is_reentrant = ACTIVE_SESSIONS.with(|set| set.borrow().contains(&session_id));
        if !is_reentrant {
            drop(managed.inner.lock());
        }
    }
}

fn get_shared_engine() -> Result<Engine> {
    if let Some(engine) = SHARED_ENGINE.get() {
        return Ok(engine.clone());
    }
    let mut config = Config::new();
    config.wasm_component_model(true);
    let engine = Engine::new(&config)?;
    let _ = SHARED_ENGINE.set(engine.clone());
    Ok(engine)
}

fn panic_payload_to_string(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "Unknown Rust panic in wasm2kt bridge".to_string()
    }
}

fn run_catching<T, F: FnOnce() -> Result<T>>(f: F) -> Result<T> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(res) => res,
        Err(payload) => Err(anyhow!("Rust panic across FFI boundary: {}", panic_payload_to_string(payload))),
    }
}

fn parse_imports_json_str(s: &str) -> Result<Vec<String>> {
    if s.trim().is_empty() {
        Ok(Vec::new())
    } else {
        serde_json::from_str(s).context("Invalid imports JSON array")
    }
}

fn parse_imports_json(imports_json_ptr: *const c_char) -> Result<Vec<String>> {
    if imports_json_ptr.is_null() {
        return Ok(Vec::new());
    }
    let s = unsafe { CStr::from_ptr(imports_json_ptr) }.to_str()?;
    parse_imports_json_str(s)
}

fn create_session_from_component(
    engine: Engine,
    component: Component,
    imports_list: Vec<String>,
    host_callback: Option<HostCallbackFn>,
    jni_callback: Option<(JavaVM, GlobalRef)>,
) -> Result<BridgeSession> {
    let mut linker = Linker::new(&engine);
    let imported_resource_types = register_host_imports(&mut linker, &component, &imports_list)?;

    let mut store = Store::new(
        &engine,
        StoreData {
            host_callback,
            jni_callback,
            resources: HashMap::new(),
            next_handle: 1,
            imported_resource_types,
            lent_host_resources: Vec::new(),
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

/// Opening a precompiled `.cwasm` runs the native code inside it without validation (Wasmtime's
/// `Component::deserialize` is `unsafe` for that reason), so it is only done when the caller asks
/// for it explicitly; `openSession` accepts `.wasm` components only (#33).
const PRECOMPILED_REFUSED: &str = "input is a precompiled Wasmtime artifact (.cwasm), which contains native code \
     that Wasmtime does not validate; open it with openPrecompiledSession, and only if it comes from a trusted source";

fn not_precompiled_component(what: &str) -> anyhow::Error {
    anyhow!("{what} is not a precompiled Wasmtime component (.cwasm) for this engine; open .wasm components with openSession")
}

fn load_component_from_path(engine: &Engine, wasm_path: &str, precompiled: bool) -> Result<Component> {
    let detected = engine.detect_precompiled_file(wasm_path).ok().flatten();
    if precompiled {
        match detected {
            Some(Precompiled::Component) => unsafe { Component::deserialize_file(engine, wasm_path) }
                .with_context(|| format!("Failed to deserialize precompiled .cwasm component from {}", wasm_path)),
            _ => Err(not_precompiled_component(wasm_path)),
        }
    } else {
        if detected.is_some() {
            bail!("{}: {}", wasm_path, PRECOMPILED_REFUSED);
        }
        Component::from_file(engine, wasm_path).with_context(|| format!("Failed to load component from {}", wasm_path))
    }
}

fn load_component_from_bytes(engine: &Engine, wasm_bytes: &[u8], precompiled: bool) -> Result<Component> {
    let detected = engine.detect_precompiled(wasm_bytes);
    if precompiled {
        match detected {
            Some(Precompiled::Component) => unsafe { Component::deserialize(engine, wasm_bytes) }
                .context("Failed to deserialize precompiled .cwasm component from bytes"),
            _ => Err(not_precompiled_component("the given bytes")),
        }
    } else {
        if detected.is_some() {
            bail!("{}", PRECOMPILED_REFUSED);
        }
        Component::from_binary(engine, wasm_bytes).context("Failed to load Wasm component from bytes")
    }
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
    precompiled: bool,
    err_buf: *mut c_char,
    err_buf_cap: usize,
) -> *mut BridgeSession {
    let res = run_catching(|| -> Result<BridgeSession> {
        if wasm_path_ptr.is_null() {
            bail!("wasm_path_ptr is null");
        }
        let wasm_path = unsafe { CStr::from_ptr(wasm_path_ptr) }
            .to_str()
            .context("Invalid UTF-8 in wasm_path")?;
        let imports_list = parse_imports_json(imports_json_ptr)?;

        let engine = get_shared_engine()?;
        let component = load_component_from_path(&engine, wasm_path, precompiled)?;

        create_session_from_component(engine, component, imports_list, host_callback, None)
    });

    match res {
        Ok(session) => register_session(session) as *mut BridgeSession,
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
    precompiled: bool,
    err_buf: *mut c_char,
    err_buf_cap: usize,
) -> *mut BridgeSession {
    let res = run_catching(|| -> Result<BridgeSession> {
        if wasm_bytes_ptr.is_null() {
            bail!("wasm_bytes_ptr is null");
        }
        let wasm_bytes = unsafe { std::slice::from_raw_parts(wasm_bytes_ptr, wasm_bytes_len) };
        let imports_list = parse_imports_json(imports_json_ptr)?;

        let engine = get_shared_engine()?;
        let component = load_component_from_bytes(&engine, wasm_bytes, precompiled)?;

        create_session_from_component(engine, component, imports_list, host_callback, None)
    });

    match res {
        Ok(session) => register_session(session) as *mut BridgeSession,
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
    let res = run_catching(|| -> Result<Vec<u8>> {
        if wasm_bytes_ptr.is_null() {
            bail!("wasm_bytes_ptr is null");
        }
        if out_len_ptr.is_null() {
            bail!("out_len_ptr is null");
        }
        let wasm_bytes = unsafe { std::slice::from_raw_parts(wasm_bytes_ptr, wasm_bytes_len) };
        let engine = get_shared_engine()?;
        engine
            .precompile_component(wasm_bytes)
            .context("Failed to precompile Wasm component into .cwasm")
    });

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
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if !ptr.is_null() {
            unsafe {
                let slice_ptr = ptr::slice_from_raw_parts_mut(ptr, len);
                drop(Box::from_raw(slice_ptr));
            }
        }
    }));
}

fn session_invoke_internal(
    session: &mut BridgeSession,
    func_spec: &str,
    args_str: &str,
) -> Result<JsonValue> {
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
        let val = json_to_val(arg_j, p_ty, &mut session.store.as_context_mut())?;
        wasm_params.push(val);
    }

    let mut wasm_results = vec![Val::Bool(false); result_types.len()];
    let called = func
        .call(&mut session.store, &wasm_params, &mut wasm_results)
        .with_context(|| format!("Wasmtime call to '{}' failed", func_spec))
        .and_then(|_| {
            func.post_return(&mut session.store)
                .with_context(|| format!("Wasmtime post_return for '{}' failed", func_spec))
        });
    // End the host borrows of this call (these handles carry no destructor).
    for lent in std::mem::take(&mut session.store.data_mut().lent_host_resources) {
        let _ = lent.resource_drop(&mut session.store);
    }
    called?;

    let mut sc = session.store.as_context_mut();
    match result_types.len() {
        0 => Ok(JsonValue::Null),
        1 => val_to_json(&wasm_results[0], &result_types[0], &mut sc),
        _ => {
            let mut tuple_out = Vec::with_capacity(result_types.len());
            for (r_val, r_ty) in wasm_results.iter().zip(result_types.iter()) {
                tuple_out.push(val_to_json(r_val, r_ty, &mut sc)?);
            }
            Ok(JsonValue::Array(tuple_out))
        }
    }
}

fn session_drop_resource_internal(session: &mut BridgeSession, handle: i32) -> Result<JsonValue> {
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
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_invoke(
    session_ptr: *mut BridgeSession,
    func_spec_ptr: *const c_char,
    args_json_ptr: *const c_char,
) -> *mut c_char {
    let res = run_catching(|| -> Result<JsonValue> {
        if func_spec_ptr.is_null() {
            bail!("func_spec_ptr is null");
        }
        if args_json_ptr.is_null() {
            bail!("args_json_ptr is null");
        }
        let func_spec = unsafe { CStr::from_ptr(func_spec_ptr) }.to_str()?;
        let args_str = unsafe { CStr::from_ptr(args_json_ptr) }.to_str()?;
        with_session_mut(session_ptr as usize, |session| {
            session_invoke_internal(session, func_spec, args_str)
        })
    });

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
    let res = run_catching(|| -> Result<JsonValue> {
        with_session_mut(session_ptr as usize, |session| {
            session_drop_resource_internal(session, handle)
        })
    });

    match res {
        Ok(val) => ok_response(val),
        Err(e) => err_response(format!("{:#}", e)),
    }
}

#[no_mangle]
pub extern "C" fn wasm2kt_session_close(session_ptr: *mut BridgeSession) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        close_session_handle(session_ptr as usize);
    }));
}

#[no_mangle]
pub extern "C" fn wasm2kt_free_string(ptr: *mut c_char) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if !ptr.is_null() {
            unsafe {
                drop(CString::from_raw(ptr));
            }
        }
    }));
}

// ============================================================================
// Exported JNI Functions for Android ART & Standard JVM (`JniWasmtimeBridge`)
// ============================================================================

fn response_to_jstring<'local>(env: &mut JNIEnv<'local>, res: Result<JsonValue>) -> jstring {
    let resp = match res {
        Ok(val) => BridgeResponse {
            ok: true,
            value: Some(val),
            error: None,
        },
        Err(e) => BridgeResponse {
            ok: false,
            value: None,
            error: Some(format!("{:#}", e)),
        },
    };
    let s = serde_json::to_string(&resp).unwrap_or_else(|e| {
        format!(r#"{{"ok":false,"error":"Failed to serialize response: {}"}}"#, e)
    });
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionOpen<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    wasm_path: JString<'local>,
    imports_json: JString<'local>,
    host_callback: JObject<'local>,
    precompiled: jboolean,
) -> jlong {
    let res = run_catching(|| -> Result<BridgeSession> {
        let wasm_path_str: String = env.get_string(&wasm_path)?.into();
        let imports_json_str: String = env.get_string(&imports_json)?.into();
        let imports_list = parse_imports_json_str(&imports_json_str)?;

        let jni_cb = if host_callback.is_null() {
            None
        } else {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(&host_callback)?;
            Some((vm, global_ref))
        };

        let engine = get_shared_engine()?;
        let component = load_component_from_path(&engine, &wasm_path_str, precompiled != 0)?;

        create_session_from_component(engine, component, imports_list, None, jni_cb)
    });

    match res {
        Ok(session) => register_session(session) as jlong,
        Err(e) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Failed to open Wasmtime session: {:#}", e),
            );
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionOpenBytes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    wasm_bytes: JByteArray<'local>,
    imports_json: JString<'local>,
    host_callback: JObject<'local>,
    precompiled: jboolean,
) -> jlong {
    let res = run_catching(|| -> Result<BridgeSession> {
        let bytes = env.convert_byte_array(&wasm_bytes)?;
        let imports_json_str: String = env.get_string(&imports_json)?.into();
        let imports_list = parse_imports_json_str(&imports_json_str)?;

        let jni_cb = if host_callback.is_null() {
            None
        } else {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(&host_callback)?;
            Some((vm, global_ref))
        };

        let engine = get_shared_engine()?;
        let component = load_component_from_bytes(&engine, &bytes, precompiled != 0)?;

        create_session_from_component(engine, component, imports_list, None, jni_cb)
    });

    match res {
        Ok(session) => register_session(session) as jlong,
        Err(e) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Failed to open Wasmtime session from bytes: {:#}", e),
            );
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionInvoke<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
    func_spec: JString<'local>,
    args_json: JString<'local>,
) -> jstring {
    let res = run_catching(|| -> Result<JsonValue> {
        let func_spec_str: String = env.get_string(&func_spec)?.into();
        let args_str: String = env.get_string(&args_json)?.into();
        with_session_mut(session_ptr as usize, |session| {
            session_invoke_internal(session, &func_spec_str, &args_str)
        })
    });
    response_to_jstring(&mut env, res)
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionDropResource<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
    handle: jint,
) -> jstring {
    let res = run_catching(|| -> Result<JsonValue> {
        with_session_mut(session_ptr as usize, |session| {
            session_drop_resource_internal(session, handle)
        })
    });
    response_to_jstring(&mut env, res)
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionClose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        close_session_handle(session_ptr as usize);
    }));
}

#[no_mangle]
pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativePrecompileComponent<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    wasm_bytes: JByteArray<'local>,
) -> jbyteArray {
    let res = run_catching(|| -> Result<Vec<u8>> {
        let bytes = env.convert_byte_array(&wasm_bytes)?;
        let engine = get_shared_engine()?;
        engine
            .precompile_component(&bytes)
            .context("Failed to precompile Wasm component into .cwasm")
    });

    match res {
        Ok(cwasm_vec) => env
            .byte_array_from_slice(&cwasm_vec)
            .map(|arr| arr.into_raw())
            .unwrap_or(ptr::null_mut()),
        Err(e) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Failed to precompile Wasm component: {:#}", e),
            );
            ptr::null_mut()
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
