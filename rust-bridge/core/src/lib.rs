//! The Wasmtime bridge shared by the generic bridge (`rust-bridge/`, any component) and the typed
//! bridges tooling generates per project (ADR-0015, ADR-0016): sessions, the binary value format,
//! host imports and the C ABI / JNI entry points, which a cdylib exports with [`export_bridge!`].

use wasmtime::error::Context;
use wasmtime::{bail, format_err as anyhow, Result};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::{JNIEnv, JavaVM};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::ptr;
use wasmtime::component::{Component, Func, Instance, Linker, LinkerInstance, ResourceAny, ResourceType, Type, Val};
use wasmtime::{AsContextMut, Config, Engine, Precompiled, Store, StoreContextMut};

pub mod typed;
pub mod wire;

#[doc(hidden)]
pub use jni;
pub use wasmtime;

use wire::{read_val, write_val, Reader};

/// A component the typed bridge was generated for (ADR-0016). Functions it does not cover are
/// called through the generic path, so it only has to know the functions it speeds up.
pub trait TypedComponent: Sync {
    /// The ID the generated Kotlin bindings open the session with (`--bridge-id`).
    fn id(&self) -> &'static str;

    /// Links the import `key` (ADR-0011) as `name` in `inst` with typed arguments, or returns false
    /// to have it linked generically.
    fn link_import(&self, inst: &mut LinkerInstance<'_, StoreData>, name: &str, key: &ImportKey) -> Result<bool>;

    /// The typed call of the export `name` (canonical: `<interface>#<function>` or a root function).
    /// `None` calls it generically. Fails if the component's type differs from the one the bridge
    /// was generated for.
    fn export(&self, name: &str, func: Func, store: &Store<StoreData>) -> Result<Option<Box<dyn TypedExport>>>;
}

/// An export called with typed arguments: reads them from the binary format, calls the function and
/// writes the results. Errors of the call itself carry [`call_failed`]'s context for `name` (the name
/// the caller resolved), like the generic path; errors in the arguments do not.
pub trait TypedExport: Send {
    fn call(&self, store: &mut Store<StoreData>, args: &[u8], name: &str) -> Result<Vec<u8>>;
}

/// The context of an error raised by Wasmtime while it calls the export `name`.
pub fn call_failed(name: &str) -> String {
    format!("Wasmtime call to '{}' failed", name)
}

/// The typed components a bridge library contains (empty for the generic bridge).
pub type Registry = &'static [&'static dyn TypedComponent];

/// Host callback function pointer type (FFM).
///
/// Receives the import's index (stable for the session, so the host can cache its lookup), its key
/// as a C string (ADR-0011) and the arguments in the binary format (ADR-0015). The host writes the
/// results (or a UTF-8 error message when it returns non-zero) into a buffer it obtains from
/// [`wasm2kt_alloc_bytes`], and stores the pointer and length in `out_ptr` / `out_len`; Rust takes
/// ownership and frees it. No results leave `out_ptr` null. There is no size limit (#35).
pub type HostCallbackFn = extern "C" fn(
    import_index: u32,
    import_key: *const c_char,
    args_ptr: *const u8,
    args_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32;

pub struct StoreData {
    pub host_callback: Option<HostCallbackFn>,
    pub jni_callback: Option<(JavaVM, GlobalRef)>,
    pub resources: HashMap<i32, ResourceAny>,
    pub next_handle: i32,
    /// Resource types the component imports, as they appear in import signatures (uninstantiated).
    /// The position of each is its host type's payload: it is linked as `host_dynamic(position)`.
    pub imported_resource_types: Vec<ResourceType>,
    /// Owned handles standing in for host borrows during the current call.
    pub lent_host_resources: Vec<ResourceAny>,
    /// Component-defined resources the current call moves out of `resources` as `own`. They go
    /// back into `resources` if the call fails, so a rejected call does not lose the host's handle.
    pub moved_handles: Vec<(i32, ResourceAny)>,
    /// Component-defined resources the current call passes as `borrow`.
    pub borrowed_handles: Vec<i32>,
    /// Host type payload of each imported resource by its key: `<interface>#<resource>` (the import
    /// name as written in the component) or `<resource>` at the root. For the typed bridges.
    pub host_payloads: HashMap<String, u32>,
}

/// The host type payload of `rt` if it is a host-defined resource: either an imported resource
/// type as it appears in the component's import signatures, or the linked host type itself (as it
/// appears in an instance's export signatures).
pub(crate) fn host_resource_payload(rt: &ResourceType, store: &StoreContextMut<'_, StoreData>) -> Option<u32> {
    let imported = &store.data().imported_resource_types;
    if let Some(i) = imported.iter().position(|t| t == rt) {
        return Some(i as u32);
    }
    (0..imported.len() as u32).find(|&i| *rt == host_resource_type(i))
}

pub struct BridgeSession {
    pub engine: Engine,
    pub component: Component,
    pub store: Store<StoreData>,
    pub instance: Instance,
    typed: Option<&'static dyn TypedComponent>,
    /// Exported functions resolved so far; `wasm2kt_session_invoke` takes their index.
    exports: Vec<Export>,
    export_indices: HashMap<String, u32>,
}

enum Export {
    Generic(ExportFn),
    Typed(String, Box<dyn TypedExport>),
}

struct ExportFn {
    name: String,
    func: Func,
    params: Vec<Type>,
    results: Vec<Type>,
}

/// The host type linked for the `payload`-th imported resource type. Each imported resource gets
/// its own type, so Wasmtime keeps them apart; the JVM side identifies objects by their rep.
fn host_resource_type(payload: u32) -> ResourceType {
    ResourceType::host_dynamic(payload)
}

/// A host import as the callbacks see it: its index in the session (so the host can cache the
/// lookup) and its key (ADR-0011).
#[derive(Clone)]
pub struct ImportKey {
    index: u32,
    key: std::sync::Arc<CString>,
}

impl ImportKey {
    fn new(keys: &mut u32, key: String) -> Result<Self> {
        let index = *keys;
        *keys += 1;
        Ok(ImportKey { index, key: std::sync::Arc::new(CString::new(key)?) })
    }

    /// The key (ADR-0011): `<interface>#<function>`, a root function's name, or a resource's drop key.
    pub fn name(&self) -> std::borrow::Cow<'_, str> {
        self.key.to_string_lossy()
    }
}

/// Links every function and resource the component imports to the host callbacks, typed where
/// `typed` covers the function. Returns the imported resource types and their payloads by key.
fn register_host_imports(
    linker: &mut Linker<StoreData>,
    component: &Component,
    typed: Option<&dyn TypedComponent>,
) -> Result<(Vec<ResourceType>, HashMap<String, u32>)> {
    let mut host_payloads: HashMap<String, u32> = HashMap::new();
    let mut imported_resource_types: Vec<ResourceType> = Vec::new();
    // Drop keys by payload: an import that aliases an earlier resource type (`(eq ...)`, e.g. a
    // world's `use store.{blob}`) must be linked as the same host type, with the same drop key.
    let mut drop_keys: Vec<ImportKey> = Vec::new();
    let mut keys = 0u32;
    let comp_ty = component.component_type();
    let engine = component.engine();

    for (import_name, import_item) in comp_ty.imports(engine) {
        match import_item.ty {
            wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) => {
                let key = ImportKey::new(&mut keys, import_name.to_string())?;
                let mut root = linker.root();
                if let Some(t) = typed {
                    if t.link_import(&mut root, import_name, &key)? {
                        continue;
                    }
                }
                let param_types: Vec<Type> = func_ty.params().map(|(_, ty)| ty).collect();
                let result_types: Vec<Type> = func_ty.results().collect();
                root.func_new(import_name, move |mut ctx, _ty, params, results| {
                    invoke_host_callback(&mut ctx, &key, params, &param_types, results, &result_types)
                })?;
            }
            wasmtime::component::types::ComponentItem::ComponentInstance(inst_ty) => {
                let mut inst = linker
                    .instance(import_name)
                    .with_context(|| format!("Failed to define linker instance '{}'", import_name))?;
                // Host import keys use the import name as written in the component: fully qualified
                // (`pkg:ns/iface@1.0`) for package interfaces, the bare name for inline ones (ADR-0011).
                let iface_key = import_name;
                for (export_fn_name, export_item) in inst_ty.exports(engine) {
                    if let wasmtime::component::types::ComponentItem::Resource(rt) = export_item.ty {
                        let (payload, drop_key) = resource_payload(
                            &mut imported_resource_types,
                            &mut drop_keys,
                            rt,
                            &mut keys,
                            format!("{}#[resource-drop]{}", iface_key, export_fn_name),
                        )?;
                        host_payloads.insert(format!("{}#{}", iface_key, export_fn_name), payload);
                        inst.resource(export_fn_name, host_resource_type(payload), host_resource_dtor(drop_key))?;
                        continue;
                    }
                    if let wasmtime::component::types::ComponentItem::ComponentFunc(func_ty) = export_item.ty {
                        let key = ImportKey::new(&mut keys, format!("{}#{}", iface_key, export_fn_name))?;
                        if let Some(t) = typed {
                            if t.link_import(&mut inst, export_fn_name, &key)? {
                                continue;
                            }
                        }
                        let param_types: Vec<Type> = func_ty.params().map(|(_, ty)| ty).collect();
                        let result_types: Vec<Type> = func_ty.results().collect();
                        inst.func_new(export_fn_name, move |mut ctx, _ty, params, results| {
                            invoke_host_callback(&mut ctx, &key, params, &param_types, results, &result_types)
                        })?;
                    }
                }
            }
            wasmtime::component::types::ComponentItem::Resource(rt) => {
                let (payload, drop_key) = resource_payload(
                    &mut imported_resource_types,
                    &mut drop_keys,
                    rt,
                    &mut keys,
                    format!("[resource-drop]{}", import_name),
                )?;
                host_payloads.insert(import_name.to_string(), payload);
                linker.root().resource(import_name, host_resource_type(payload), host_resource_dtor(drop_key))?;
            }
            _ => {}
        }
    }

    Ok((imported_resource_types, host_payloads))
}

/// Called when the guest drops its last owning handle to a host resource: forwards
/// `[resource-drop]<name>` with the rep to the JVM host, which closes the object.
/// The host type payload and drop key for an imported resource type: a new one, or the earlier
/// one if `rt` aliases a resource type imported before (aliases always refer backwards).
fn resource_payload(
    types: &mut Vec<ResourceType>,
    drop_keys: &mut Vec<ImportKey>,
    rt: ResourceType,
    keys: &mut u32,
    drop_key: String,
) -> Result<(u32, ImportKey)> {
    if let Some(i) = types.iter().position(|t| *t == rt) {
        return Ok((i as u32, drop_keys[i].clone()));
    }
    let key = ImportKey::new(keys, drop_key)?;
    types.push(rt);
    drop_keys.push(key.clone());
    Ok(((types.len() - 1) as u32, key))
}

fn host_resource_dtor(
    key: ImportKey,
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
    import: &ImportKey,
    params: &[Val],
    param_types: &[Type],
    results: &mut [Val],
    result_types: &[Type],
) -> Result<()> {
    let mut args = Vec::new();
    for (p, p_ty) in params.iter().zip(param_types.iter()) {
        write_val(&mut args, p, p_ty, &mut ctx.as_context_mut())?;
    }
    let response = call_host(ctx, import, &args)?;
    let mut reader = Reader::new(&response);
    for (slot, r_ty) in results.iter_mut().zip(result_types.iter()) {
        *slot = read_val(&mut reader, r_ty, &mut ctx.as_context_mut())
            .with_context(|| format!("Invalid results from host import '{}'", import.name()))?;
    }
    reader.finish().with_context(|| format!("Invalid results from host import '{}'", import.name()))
}

/// Calls the host import `import` with its arguments in the binary format and returns its results
/// in the same format.
pub fn call_host(ctx: &mut StoreContextMut<'_, StoreData>, import: &ImportKey, args: &[u8]) -> Result<Vec<u8>> {
    let response: Vec<u8> = if let Some(cb) = ctx.data().host_callback {
        let mut out_ptr: *mut u8 = ptr::null_mut();
        let mut out_len: usize = 0;
        let status = cb(import.index, import.key.as_ptr(), args.as_ptr(), args.len(), &mut out_ptr, &mut out_len);
        let response = if out_ptr.is_null() {
            Vec::new()
        } else {
            unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(out_ptr, out_len)) }.into_vec()
        };
        if status != 0 {
            bail!("Host import '{}' failed: {}", import.name(), String::from_utf8_lossy(&response));
        }
        response
    } else if let Some((ref vm, ref callback_ref)) = ctx.data().jni_callback {
        let mut env = vm
            .get_env()
            .or_else(|_| vm.attach_current_thread_permanently())
            .context("Failed to acquire JNIEnv for host callback")?;
        env.with_local_frame(16, |env| -> Result<Vec<u8>> {
            let j_key: JObject = env.new_string(import.name())?.into();
            let j_args: JObject = env.byte_array_from_slice(args)?.into();
            let call_res = env.call_method(
                callback_ref,
                "onHostCall",
                "(ILjava/lang/String;[B)[B",
                &[JValue::Int(import.index as i32), JValue::Object(&j_key), JValue::Object(&j_args)],
            );
            if env.exception_check().unwrap_or(false) {
                let exc_msg = extract_and_clear_jni_exception(env);
                bail!("Host import '{}' threw an exception in JVM/Android host: {}", import.name(), exc_msg);
            }
            let ret_obj = call_res
                .with_context(|| format!("JNI call_method onHostCall failed for '{}'", import.name()))?
                .l()?;
            if ret_obj.is_null() {
                return Ok(Vec::new());
            }
            Ok(env.convert_byte_array(JByteArray::from(ret_obj))?)
        })?
    } else {
        bail!("Component imports '{}' but no host imports were provided", import.name())
    };
    Ok(response)
}

// Shared engine, session registry, and the C ABI functions `export_bridge!` exports.

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

/// The typed component `id` in `registry`; `None` opens a generic session.
fn typed_component(registry: Registry, id: Option<&str>) -> Result<Option<&'static dyn TypedComponent>> {
    match id {
        None => Ok(None),
        Some(id) => registry.iter().copied().find(|c| c.id() == id).map(Some).ok_or_else(|| {
            let known: Vec<_> = registry.iter().map(|c| c.id()).collect();
            if known.is_empty() {
                anyhow!("Component '{}' needs a typed bridge, but this is the generic bridge library", id)
            } else {
                anyhow!("This bridge library has no typed component '{}' (it has: {})", id, known.join(", "))
            }
        }),
    }
}

fn create_session_from_component(
    engine: Engine,
    component: Component,
    typed: Option<&'static dyn TypedComponent>,
    host_callback: Option<HostCallbackFn>,
    jni_callback: Option<(JavaVM, GlobalRef)>,
) -> Result<BridgeSession> {
    let mut linker = Linker::new(&engine);
    let (imported_resource_types, host_payloads) = register_host_imports(&mut linker, &component, typed)?;

    let mut store = Store::new(
        &engine,
        StoreData {
            host_callback,
            jni_callback,
            resources: HashMap::new(),
            next_handle: 1,
            imported_resource_types,
            lent_host_resources: Vec::new(),
            moved_handles: Vec::new(),
            borrowed_handles: Vec::new(),
            host_payloads,
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
        typed,
        exports: Vec::new(),
        export_indices: HashMap::new(),
    })
}

/// Opening a precompiled `.cwasm` runs the native code inside it without validation (Wasmtime's
/// `Component::deserialize` is `unsafe` for that reason), so it is only done when the caller asks
/// for it explicitly; `openSession` accepts `.wasm` components only (#33).
const PRECOMPILED_REFUSED: &str = "input is a precompiled Wasmtime artifact (.cwasm), which contains native code \
     that Wasmtime does not validate; open it with openPrecompiledSession, and only if it comes from a trusted source";

fn not_precompiled_component(what: &str) -> wasmtime::Error {
    anyhow!("{what} is not a precompiled Wasmtime component (.cwasm) for this engine; open .wasm components with openSession")
}

fn load_component_from_path(engine: &Engine, wasm_path: &str, precompiled: bool) -> Result<Component> {
    let detected = Engine::detect_precompiled_file(wasm_path).ok().flatten();
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
    let detected = Engine::detect_precompiled(wasm_bytes);
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

fn write_err_buf(err: &wasmtime::Error, err_buf: *mut c_char, err_buf_cap: usize) {
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

/// `wasm2kt_session_open`: opens `wasm_path_ptr` as the typed component `component_id_ptr` of
/// `registry`, or generically if it is null.
#[doc(hidden)]
pub fn ffi_session_open(
    registry: Registry,
    component_id_ptr: *const c_char,
    wasm_path_ptr: *const c_char,
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

        let typed = typed_component(registry, c_str_opt(component_id_ptr)?)?;
        let engine = get_shared_engine()?;
        let component = load_component_from_path(&engine, wasm_path, precompiled)?;

        create_session_from_component(engine, component, typed, host_callback, None)
    });

    match res {
        Ok(session) => register_session(session) as *mut BridgeSession,
        Err(e) => {
            write_err_buf(&e, err_buf, err_buf_cap);
            ptr::null_mut()
        }
    }
}

#[doc(hidden)]
pub fn ffi_session_open_bytes(
    registry: Registry,
    component_id_ptr: *const c_char,
    wasm_bytes_ptr: *const u8,
    wasm_bytes_len: usize,
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

        let typed = typed_component(registry, c_str_opt(component_id_ptr)?)?;
        let engine = get_shared_engine()?;
        let component = load_component_from_bytes(&engine, wasm_bytes, precompiled)?;

        create_session_from_component(engine, component, typed, host_callback, None)
    });

    match res {
        Ok(session) => register_session(session) as *mut BridgeSession,
        Err(e) => {
            write_err_buf(&e, err_buf, err_buf_cap);
            ptr::null_mut()
        }
    }
}

#[doc(hidden)]
pub fn ffi_precompile_component(
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

/// Allocates `len` zeroed bytes for a host callback response (see [`HostCallbackFn`]); Rust frees
/// them after reading the response. Returns null for `len == 0` or if allocation panics.
#[doc(hidden)]
pub fn ffi_alloc_bytes(len: usize) -> *mut u8 {
    if len == 0 {
        return ptr::null_mut();
    }
    std::panic::catch_unwind(|| Box::into_raw(vec![0u8; len].into_boxed_slice()) as *mut u8).unwrap_or(ptr::null_mut())
}

#[doc(hidden)]
pub fn ffi_free_bytes(ptr: *mut u8, len: usize) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if !ptr.is_null() {
            unsafe {
                let slice_ptr = ptr::slice_from_raw_parts_mut(ptr, len);
                drop(Box::from_raw(slice_ptr));
            }
        }
    }));
}

fn session_resolve_internal(session: &mut BridgeSession, name: &str) -> Result<u32> {
    if let Some(&index) = session.export_indices.get(name) {
        return Ok(index);
    }
    let (canonical, func) = resolve_exported_func(&session.component, &session.instance, &mut session.store, name)?;
    let typed = match session.typed {
        Some(t) => t
            .export(&canonical, func, &session.store)
            .with_context(|| format!("The typed bridge for '{}' does not match the component's '{}'; rebuild the bridge", t.id(), canonical))?,
        None => None,
    };
    let export = match typed {
        Some(call) => Export::Typed(name.to_string(), call),
        None => {
            let func_ty = func.ty(&session.store);
            Export::Generic(ExportFn {
                name: name.to_string(),
                func,
                params: func_ty.params().map(|(_, ty)| ty).collect(),
                results: func_ty.results().collect(),
            })
        }
    };
    let index = session.exports.len() as u32;
    session.exports.push(export);
    session.export_indices.insert(name.to_string(), index);
    Ok(index)
}

/// Reads the arguments, lowers them and calls the function (Wasmtime runs post-return itself).
fn lower_and_call(store: &mut Store<StoreData>, export: &ExportFn, args: &[u8], results: &mut [Val]) -> Result<()> {
    let mut reader = Reader::new(args);
    let mut params = Vec::with_capacity(export.params.len());
    for p_ty in &export.params {
        params.push(read_val(&mut reader, p_ty, &mut store.as_context_mut())?);
    }
    reader.finish().with_context(|| format!("Invalid arguments for '{}'", export.name))?;
    export
        .func
        .call(&mut *store, &params, results)
        .with_context(|| call_failed(&export.name))
}

fn session_invoke_internal(session: &mut BridgeSession, index: u32, args: &[u8]) -> Result<Vec<u8>> {
    let export = session
        .exports
        .get(index as usize)
        .ok_or_else(|| anyhow!("Unknown function index {}", index))?;
    let (called, generic) = match export {
        Export::Generic(export) => {
            let mut wasm_results = vec![Val::Bool(false); export.results.len()];
            let called = lower_and_call(&mut session.store, export, args, &mut wasm_results);
            (called.map(|()| Vec::new()), Some((export, wasm_results)))
        }
        Export::Typed(name, call) => (call.call(&mut session.store, args, name), None),
    };
    // End the host borrows of this call (these handles carry no destructor).
    for lent in std::mem::take(&mut session.store.data_mut().lent_host_resources) {
        let _ = lent.resource_drop(&mut session.store);
    }
    // A failed call gives the moved handles back (e.g. a later argument was invalid, or Wasmtime
    // refused to move a handle that the same call borrows), matching the Chicory backend.
    session.store.data_mut().borrowed_handles.clear();
    let moved = std::mem::take(&mut session.store.data_mut().moved_handles);
    if called.is_err() {
        session.store.data_mut().resources.extend(moved);
    }
    let out = called?;
    let Some((export, wasm_results)) = generic else {
        return Ok(out);
    };

    let mut out = Vec::new();
    let mut sc = session.store.as_context_mut();
    for (r_val, r_ty) in wasm_results.iter().zip(export.results.iter()) {
        write_val(&mut out, r_val, r_ty, &mut sc)?;
    }
    Ok(out)
}

fn session_drop_resource_internal(session: &mut BridgeSession, handle: i32) -> Result<Vec<u8>> {
    let res_any = session
        .store
        .data_mut()
        .resources
        .remove(&handle)
        .ok_or_else(|| anyhow!("Resource handle {} is not active or already dropped", handle))?;
    res_any
        .resource_drop(&mut session.store)
        .with_context(|| format!("Failed to drop Wasmtime resource handle {}", handle))?;
    Ok(Vec::new())
}

/// Hands `bytes` to the caller through `out_ptr` / `out_len`; it frees them with
/// [`wasm2kt_free_bytes`]. Nothing to hand over leaves `out_ptr` null.
fn write_out(bytes: Vec<u8>, out_ptr: *mut *mut u8, out_len: *mut usize) {
    if out_ptr.is_null() || out_len.is_null() {
        return;
    }
    unsafe {
        if bytes.is_empty() {
            *out_ptr = ptr::null_mut();
            *out_len = 0;
        } else {
            let boxed = bytes.into_boxed_slice();
            *out_len = boxed.len();
            *out_ptr = Box::into_raw(boxed) as *mut u8;
        }
    }
}

/// 0 with the results, or 1 with the error message, in `out_ptr` / `out_len`.
fn finish_call(res: Result<Vec<u8>>, out_ptr: *mut *mut u8, out_len: *mut usize) -> i32 {
    match res {
        Ok(bytes) => {
            write_out(bytes, out_ptr, out_len);
            0
        }
        Err(e) => {
            write_out(format!("{:#}", e).into_bytes(), out_ptr, out_len);
            1
        }
    }
}

/// Resolves an exported function by name (ADR-0011) into `out_index`, for `wasm2kt_session_invoke`.
#[doc(hidden)]
pub fn ffi_session_resolve(
    session_ptr: *mut BridgeSession,
    name_ptr: *const c_char,
    out_index: *mut u32,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let res = run_catching(|| -> Result<Vec<u8>> {
        if name_ptr.is_null() || out_index.is_null() {
            bail!("name_ptr or out_index is null");
        }
        let name = unsafe { CStr::from_ptr(name_ptr) }.to_str()?;
        let index = with_session_mut(session_ptr as usize, |session| session_resolve_internal(session, name))?;
        unsafe {
            *out_index = index;
        }
        Ok(Vec::new())
    });
    finish_call(res, out_ptr, out_len)
}

/// Calls the resolved export `index` with its arguments in the binary format (ADR-0015).
#[doc(hidden)]
pub fn ffi_session_invoke(
    session_ptr: *mut BridgeSession,
    index: u32,
    args_ptr: *const u8,
    args_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let res = run_catching(|| -> Result<Vec<u8>> {
        let args = if args_len == 0 || args_ptr.is_null() {
            &[][..]
        } else {
            unsafe { std::slice::from_raw_parts(args_ptr, args_len) }
        };
        with_session_mut(session_ptr as usize, |session| session_invoke_internal(session, index, args))
    });
    finish_call(res, out_ptr, out_len)
}

#[doc(hidden)]
pub fn ffi_session_drop_resource(
    session_ptr: *mut BridgeSession,
    handle: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let res = run_catching(|| -> Result<Vec<u8>> {
        with_session_mut(session_ptr as usize, |session| session_drop_resource_internal(session, handle))
    });
    finish_call(res, out_ptr, out_len)
}

#[doc(hidden)]
pub fn ffi_session_close(session_ptr: *mut BridgeSession) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        close_session_handle(session_ptr as usize);
    }));
}

// JNI entry points for `JniWasmtimeBridge` (Android ART and the JVM); exported by `export_bridge!`.

/// Throws `exception` with the error's message into the JVM and returns `fallback`, or returns the value.
fn or_throw<'local, T>(env: &mut JNIEnv<'local>, res: Result<T>, exception: &str, fallback: T) -> T {
    match res {
        Ok(v) => v,
        Err(e) => {
            if !env.exception_check().unwrap_or(false) {
                let _ = env.throw_new(exception, format!("{:#}", e));
            }
            fallback
        }
    }
}

fn jni_string_opt<'local>(env: &mut JNIEnv<'local>, s: &JString<'local>) -> Result<Option<String>> {
    if s.is_null() {
        return Ok(None);
    }
    Ok(Some(env.get_string(s)?.into()))
}

fn c_str_opt<'a>(p: *const c_char) -> Result<Option<&'a str>> {
    if p.is_null() {
        return Ok(None);
    }
    Ok(Some(unsafe { CStr::from_ptr(p) }.to_str().context("Invalid UTF-8 in component id")?))
}

fn jni_callback<'local>(env: &mut JNIEnv<'local>, host_callback: &JObject<'local>) -> Result<Option<(JavaVM, GlobalRef)>> {
    if host_callback.is_null() {
        return Ok(None);
    }
    Ok(Some((env.get_java_vm()?, env.new_global_ref(host_callback)?)))
}

#[doc(hidden)]
pub fn jni_session_open<'local>(
    registry: Registry,
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    component_id: JString<'local>,
    wasm_path: JString<'local>,
    host_callback: JObject<'local>,
    precompiled: jboolean,
) -> jlong {
    let res = run_catching(|| -> Result<BridgeSession> {
        let wasm_path_str: String = env.get_string(&wasm_path)?.into();
        let id = jni_string_opt(&mut env, &component_id)?;
        let typed = typed_component(registry, id.as_deref())?;
        let jni_cb = jni_callback(&mut env, &host_callback)?;
        let engine = get_shared_engine()?;
        let component = load_component_from_path(&engine, &wasm_path_str, precompiled != 0)?;
        create_session_from_component(engine, component, typed, None, jni_cb)
    })
    .map(|session| register_session(session) as jlong)
    .context("Failed to open Wasmtime session");
    or_throw(&mut env, res, "java/lang/IllegalStateException", 0)
}

#[doc(hidden)]
pub fn jni_session_open_bytes<'local>(
    registry: Registry,
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    component_id: JString<'local>,
    wasm_bytes: JByteArray<'local>,
    host_callback: JObject<'local>,
    precompiled: jboolean,
) -> jlong {
    let res = run_catching(|| -> Result<BridgeSession> {
        let bytes = env.convert_byte_array(&wasm_bytes)?;
        let id = jni_string_opt(&mut env, &component_id)?;
        let typed = typed_component(registry, id.as_deref())?;
        let jni_cb = jni_callback(&mut env, &host_callback)?;
        let engine = get_shared_engine()?;
        let component = load_component_from_bytes(&engine, &bytes, precompiled != 0)?;
        create_session_from_component(engine, component, typed, None, jni_cb)
    })
    .map(|session| register_session(session) as jlong)
    .context("Failed to open Wasmtime session from bytes");
    or_throw(&mut env, res, "java/lang/IllegalStateException", 0)
}

#[doc(hidden)]
pub fn jni_session_resolve<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
    name: JString<'local>,
) -> jint {
    let res = run_catching(|| -> Result<jint> {
        let name: String = env.get_string(&name)?.into();
        Ok(with_session_mut(session_ptr as usize, |session| session_resolve_internal(session, &name))? as jint)
    });
    or_throw(&mut env, res, "java/lang/IllegalArgumentException", -1)
}

#[doc(hidden)]
pub fn jni_session_invoke<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
    index: jint,
    args: JByteArray<'local>,
    args_len: jint,
) -> jbyteArray {
    let res = run_catching(|| -> Result<Vec<u8>> {
        let mut buf = vec![0i8; args_len.max(0) as usize];
        env.get_byte_array_region(&args, 0, &mut buf)?;
        let bytes: Vec<u8> = buf.into_iter().map(|b| b as u8).collect();
        with_session_mut(session_ptr as usize, |session| session_invoke_internal(session, index as u32, &bytes))
    })
    .and_then(|out| Ok(env.byte_array_from_slice(&out)?.into_raw()));
    or_throw(&mut env, res, "java/lang/IllegalStateException", ptr::null_mut())
}

#[doc(hidden)]
pub fn jni_session_drop_resource<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
    handle: jint,
) {
    let res = run_catching(|| -> Result<()> {
        with_session_mut(session_ptr as usize, |session| session_drop_resource_internal(session, handle))?;
        Ok(())
    });
    or_throw(&mut env, res, "java/lang/IllegalStateException", ())
}

#[doc(hidden)]
pub fn jni_session_close<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session_ptr: jlong,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        close_session_handle(session_ptr as usize);
    }));
}

#[doc(hidden)]
pub fn jni_precompile_component<'local>(
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
) -> Result<(String, wasmtime::component::Func)> {
    // The fully qualified form (`pkg:ns/iface@1.0#fn`) is canonical. An interface written without
    // its version or as its short name (`iface#fn`), and a bare function name searched across
    // exported interfaces, are accepted only when they match exactly one export (ADR-0011).
    let comp_ty = component.component_type();
    let engine = component.engine();
    let interface_exports: Vec<String> = comp_ty
        .exports(engine)
        .filter(|(_, item)| matches!(item.ty, wasmtime::component::types::ComponentItem::ComponentInstance(_)))
        .map(|(name, _)| name.to_string())
        .collect();

    let lookup = |store: &mut Store<StoreData>, iface: &str, fn_name: &str| {
        let iface_idx = instance.get_export_index(&mut *store, None, iface)?;
        let fn_idx = instance.get_export_index(&mut *store, Some(&iface_idx), fn_name)?;
        instance.get_func(&mut *store, fn_idx)
    };

    let (candidates, not_found) = if let Some((iface_name, fn_name)) = func_spec.split_once('#') {
        if let Some(f) = lookup(store, iface_name, fn_name) {
            return Ok((func_spec.to_string(), f));
        }
        let mut found = Vec::new();
        for export_name in &interface_exports {
            if interface_name_matches(export_name, iface_name) {
                if let Some(f) = lookup(store, export_name, fn_name) {
                    found.push((format!("{}#{}", export_name, fn_name), f));
                }
            }
        }
        (
            found,
            format!("Exported function '{}' not found in interface '{}'", fn_name, iface_name),
        )
    } else {
        if let Some(f) = instance.get_func(&mut *store, func_spec) {
            return Ok((func_spec.to_string(), f));
        }
        let mut found = Vec::new();
        for export_name in &interface_exports {
            if let Some(f) = lookup(store, export_name, func_spec) {
                found.push((format!("{}#{}", export_name, func_spec), f));
            }
        }
        (found, format!("Exported function '{}' not found in component", func_spec))
    };
    match candidates.len() {
        0 => bail!(not_found),
        1 => Ok(candidates.into_iter().next().unwrap()),
        _ => bail!(
            "Function name '{}' is ambiguous; use one of: {}",
            func_spec,
            candidates.iter().map(|(name, _)| name.as_str()).collect::<Vec<_>>().join(", ")
        ),
    }
}

/// Whether `iface` names the exported interface `export_name` (`pkg:ns/iface@1.0`) without its
/// version (`pkg:ns/iface`) or by its short name (`iface`).
fn interface_name_matches(export_name: &str, iface: &str) -> bool {
    let unversioned = export_name.split('@').next().unwrap_or(export_name);
    let short = unversioned.split_once('/').map(|(_, s)| s).unwrap_or(unversioned);
    unversioned == iface || short == iface
}

mod exports;
