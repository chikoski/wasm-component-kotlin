//! `wasm2kt-decompose`: splits a WebAssembly component into its core modules
//! and a JSON instantiation plan that wasm2kt's Chicory backend replays at
//! runtime.
//!
//! Like jco's `js-component-bindgen`, this relies on `wasmtime-environ` to
//! flatten an arbitrarily nested component into a linear list of
//! initializers (instantiate module, extract memory/realloc/post-return,
//! ...). We then serialize a deliberately small, explicit subset of that
//! representation so the Kotlin side never depends on wasmtime-environ's
//! internal serde layout.
//!
//! Usage: `wasm2kt-decompose <component.wasm> <out-dir>`
//! Output: `<out-dir>/plan.json`, `<out-dir>/core<N>.wasm` and `<out-dir>/wit.json` (the
//! component's WIT in the same JSON form as `wasm-tools component wit --json`, so binding
//! generation needs no external wasm-tools). A component whose WIT cannot be decoded gets
//! `<out-dir>/wit-error.txt` with the reason instead of `wit.json`.

use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Map, Value};
use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap};
use std::path::Path;
use wasmtime_environ::component::*;
use wasmtime_environ::wasmparser::{Validator, WasmFeatures};
use wasmtime_environ::{EntityIndex, EntityType, ModuleTranslation, PrimaryMap, ScopeVec, Tunables};

/// Bump whenever the plan.json schema changes incompatibly.
const PLAN_VERSION: u32 = 5;

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        bail!("usage: {} <component.wasm> <out-dir>", args.first().map(String::as_str).unwrap_or("wasm2kt-decompose"));
    }
    let bytes = std::fs::read(&args[1]).with_context(|| format!("reading {}", args[1]))?;
    let out_dir = Path::new(&args[2]);
    std::fs::create_dir_all(out_dir)?;

    let (plan, modules) = decompose(&bytes)?;
    for (file, wasm) in &modules {
        std::fs::write(out_dir.join(file), wasm)?;
    }
    std::fs::write(out_dir.join("plan.json"), serde_json::to_string_pretty(&plan)?)?;
    // Running a component needs only the plan. Its WIT, for binding generation, cannot be decoded
    // for every valid component (e.g. one that exports bare types, as the Component Model reference
    // tests do, #30): such a component gets `wit-error.txt` instead of `wit.json`.
    let _ = std::fs::remove_file(out_dir.join("wit-error.txt"));
    match wit_json(&bytes) {
        Ok(json) => std::fs::write(out_dir.join("wit.json"), json)?,
        Err(e) => {
            let _ = std::fs::remove_file(out_dir.join("wit.json"));
            eprintln!("warning: {:#}", e);
            std::fs::write(out_dir.join("wit-error.txt"), format!("{:#}\n", e))?;
        }
    }
    Ok(())
}

/// The component's type-level WIT, serialized exactly like `wasm-tools component wit --json`.
pub fn wit_json(component_bytes: &[u8]) -> Result<String> {
    match wit_component::decode(component_bytes).context("failed to decode the component's WIT")? {
        wit_component::DecodedWasm::Component(resolve, _) => Ok(serde_json::to_string_pretty(&resolve)?),
        wit_component::DecodedWasm::WitPackage(..) => bail!("expected a component, found a WIT package"),
    }
}

/// Core module files of a decomposed component: `(file name, core module bytes)`.
type ModuleFiles = Vec<(String, Vec<u8>)>;

/// Returns the plan and the core module files.
pub fn decompose(component_bytes: &[u8]) -> Result<(Value, ModuleFiles)> {
    let scope = ScopeVec::new();
    let mut tunables = Tunables::default_u32();
    // Only a component that uses the async ABI (#16) gets wasmtime-environ's concurrency support.
    // With it, the fused adapters between child components also save and restore the task's
    // `context.get`/`context.set` slots and push a task for every synchronous call, so a
    // synchronous component keeps the simpler adapters (and its plan) it had before plan v5.
    tunables.concurrency_support = uses_async(component_bytes)?;
    // Chicory executes plain core wasm; keep exceptions off so wasmtime's
    // generated adapters don't emit `try_table` barriers it can't run.
    // Multi-memory is on because wasmtime-environ validates the adapters it fuses
    // between two child components with this validator, and an adapter that
    // copies a string or list imports both components' memories (#47).
    let features = (WasmFeatures::WASM2 | WasmFeatures::COMPONENT_MODEL | WasmFeatures::MULTI_MEMORY | async_features())
        .difference(WasmFeatures::EXCEPTIONS);
    validate_task_return(component_bytes, features)?;
    let mut validator = Validator::new_with_features(features);
    let mut types = ComponentTypesBuilder::new(&validator);
    let (translation, modules) = Translator::new(&tunables, &mut validator, &mut types, &scope)
        .translate(component_bytes)
        .map_err(|e| anyhow!(e).context("failed to translate component"))?;
    let (types, _) = types.finish(&translation.component);

    let ctx = Ctx {
        component: &translation.component,
        types: &types,
        modules: &modules,
        instance_modules: Vec::new(),
        tables: RefCell::default(),
        stream_tables: RefCell::default(),
        future_tables: RefCell::default(),
        error_context_tables: RefCell::default(),
    };
    ctx.build(&translation)
}

/// Rejects `canon task.return` definitions whose result contains a `borrow` handle anywhere, which
/// wasmparser 0.254 does not reject yet (`async/validate-task-return.wast`).
///
/// Must run over `ComponentCanonicalSection` via `Validator` rather than only `translation.trampolines`,
/// because `wasmtime-environ` dead-code-eliminates unreferenced canonical built-ins (as in
/// `async/validate-task-return.wast:4,9`).
fn validate_task_return(component_bytes: &[u8], features: WasmFeatures) -> Result<()> {
    use wasmtime_environ::wasmparser::component_types::{
        ComponentDefinedType as ValidatedDefinedType, ComponentDefinedTypeId, ComponentValType as ValidatedValType,
    };
    use wasmtime_environ::wasmparser::types::TypesRef;
    use wasmtime_environ::wasmparser::{CanonicalFunction, ComponentValType, Parser, Payload};

    fn val_contains_borrow(types: &TypesRef<'_>, ty: &ValidatedValType) -> Result<bool> {
        match ty {
            ValidatedValType::Primitive(_) => Ok(false),
            ValidatedValType::Type(id) => defined_contains_borrow(types, *id),
        }
    }

    fn defined_contains_borrow(types: &TypesRef<'_>, id: ComponentDefinedTypeId) -> Result<bool> {
        let Some(ty) = types.get(id) else {
            bail!("unknown component defined type in `task.return`");
        };
        match ty {
            ValidatedDefinedType::Primitive(_)
            | ValidatedDefinedType::Flags(_)
            | ValidatedDefinedType::Enum(_)
            | ValidatedDefinedType::Own(_) => Ok(false),
            ValidatedDefinedType::Borrow(_) => Ok(true),
            ValidatedDefinedType::Record(r) => {
                for f in r.fields.values() {
                    if val_contains_borrow(types, f)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            ValidatedDefinedType::Variant(v) => {
                for c in v.cases.values() {
                    if let Some(t) = c.ty.as_ref() {
                        if val_contains_borrow(types, t)? {
                            return Ok(true);
                        }
                    }
                }
                Ok(false)
            }
            ValidatedDefinedType::List { element, .. }
            | ValidatedDefinedType::FixedLengthList { element, .. }
            | ValidatedDefinedType::Option { ty: element, .. } => val_contains_borrow(types, element),
            ValidatedDefinedType::Map { key, value, .. } => {
                Ok(val_contains_borrow(types, key)? || val_contains_borrow(types, value)?)
            }
            ValidatedDefinedType::Tuple(t) => {
                for elem in &t.types {
                    if val_contains_borrow(types, elem)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            ValidatedDefinedType::Result { ok, err, .. } => {
                if let Some(t) = ok.as_ref() {
                    if val_contains_borrow(types, t)? {
                        return Ok(true);
                    }
                }
                if let Some(t) = err.as_ref() {
                    if val_contains_borrow(types, t)? {
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            ValidatedDefinedType::Future { ty, .. } | ValidatedDefinedType::Stream { ty, .. } => match ty.as_ref() {
                Some(t) => val_contains_borrow(types, t),
                None => Ok(false),
            },
        }
    }

    let mut parser = Parser::new(0);
    parser.set_features(features);
    let mut validator = Validator::new_with_features(features);
    for payload in parser.parse_all(component_bytes) {
        let payload = payload?;
        validator.payload(&payload)?;
        if let Payload::ComponentCanonicalSection(reader) = payload {
            if let Some(types) = validator.types(0) {
                for func in reader {
                    if let CanonicalFunction::TaskReturn { result: Some(ComponentValType::Type(idx)), .. } = func? {
                        let id = types.component_defined_type_at(idx);
                        if defined_contains_borrow(&types, id)? {
                            bail!("`task.return` result cannot contain a `borrow` type");
                        }
                    }
                }
            }
        }
    }
    Ok(())
}

/// The Component Model async features (#16): the async ABI with streams and futures, the stackful
/// ABI, the cooperative threads built-ins and `error-context`. Also `map<K, V>`, which the runtime
/// does not support (#71) but which should be reported as such rather than fail to decompose.
fn async_features() -> WasmFeatures {
    WasmFeatures::CM_MAP
        | WasmFeatures::CM_ASYNC
        | WasmFeatures::CM_ASYNC_STACKFUL
        | WasmFeatures::CM_MORE_ASYNC_BUILTINS
        | WasmFeatures::CM_THREADING
        | WasmFeatures::CM_ERROR_CONTEXT
}

/// Whether the component, or one nested in it, uses the async ABI: an async lift or lower, an async
/// built-in (`task.return`, `stream.new`, ...), or an `async` function type.
fn uses_async(component_bytes: &[u8]) -> Result<bool> {
    use wasmtime_environ::wasmparser::{
        CanonicalFunction, CanonicalOption, ComponentType, ComponentTypeDeclaration, InstanceTypeDeclaration, Parser, Payload,
    };
    let async_options = |options: &[CanonicalOption]| options.iter().any(|o| matches!(o, CanonicalOption::Async | CanonicalOption::Callback(_)));
    fn async_type(t: &ComponentType) -> bool {
        match t {
            ComponentType::Func(f) => f.async_,
            ComponentType::Component(decls) => decls.iter().any(|d| match d {
                ComponentTypeDeclaration::Type(t) => async_type(t),
                _ => false,
            }),
            ComponentType::Instance(decls) => decls.iter().any(|d| match d {
                InstanceTypeDeclaration::Type(t) => async_type(t),
                _ => false,
            }),
            _ => false,
        }
    }
    for payload in Parser::new(0).parse_all(component_bytes) {
        match payload? {
            Payload::ComponentCanonicalSection(reader) => {
                for f in reader {
                    let uses = match f? {
                        CanonicalFunction::Lift { options, .. } | CanonicalFunction::Lower { options, .. } => async_options(&options),
                        CanonicalFunction::ResourceNew { .. } | CanonicalFunction::ResourceDrop { .. } | CanonicalFunction::ResourceRep { .. } => false,
                        _ => true,
                    };
                    if uses {
                        return Ok(true);
                    }
                }
            }
            Payload::ComponentTypeSection(reader) => {
                for t in reader {
                    if async_type(&t?) {
                        return Ok(true);
                    }
                }
            }
            _ => {}
        }
    }
    Ok(false)
}

struct Ctx<'a, 'data> {
    component: &'a Component,
    types: &'a ComponentTypes,
    modules: &'a PrimaryMap<StaticModuleIndex, ModuleTranslation<'data>>,
    /// RuntimeInstanceIndex -> static module it instantiates (filled while walking initializers).
    instance_modules: Vec<StaticModuleIndex>,
    /// Resource tables referenced anywhere in the plan.
    tables: RefCell<BTreeSet<u32>>,
    /// Stream and future tables referenced anywhere in the plan (plan v5).
    stream_tables: RefCell<BTreeSet<u32>>,
    future_tables: RefCell<BTreeSet<u32>>,
    error_context_tables: RefCell<BTreeSet<u32>>,
}

impl Ctx<'_, '_> {
    fn build(mut self, translation: &ComponentTranslation) -> Result<(Value, ModuleFiles)> {
        let mut files = Vec::new();
        let mut module_json = Vec::new();
        for (idx, m) in self.modules.iter() {
            let file = format!("core{}.wasm", idx.as_u32());
            files.push((file.clone(), m.wasm.to_vec()));
            let imports: Vec<Value> = m
                .module
                .imports()
                .map(|(module, name, ty)| json!({ "module": module, "name": name, "kind": entity_kind(&ty) }))
                .collect();
            module_json.push(json!({ "index": idx.as_u32(), "file": file, "imports": imports }));
        }

        let mut lowered_to_import: HashMap<u32, u32> = HashMap::new();
        let mut inits = Vec::new();
        for init in &self.component.initializers {
            match init {
                GlobalInitializer::InstantiateModule(InstantiateModule::Static(midx, args), _) => {
                    let instance = self.instance_modules.len() as u32;
                    let module = &self.modules[*midx].module;
                    let mut arg_json = Vec::new();
                    for ((imod, iname, _), def) in module.imports().zip(args.iter()) {
                        arg_json.push(json!({ "module": imod, "name": iname, "def": self.core_def(def)? }));
                    }
                    inits.push(json!({ "op": "instantiate", "instance": instance, "module": midx.as_u32(), "args": arg_json }));
                    self.instance_modules.push(*midx);
                }
                GlobalInitializer::InstantiateModule(InstantiateModule::Import(..), _) => {
                    bail!("components that import core modules are not supported")
                }
                GlobalInitializer::LowerImport { index, import } => {
                    lowered_to_import.insert(index.as_u32(), import.as_u32());
                }
                GlobalInitializer::ExtractMemory(m) => inits.push(json!({
                    "op": "extract-memory", "index": m.index.as_u32(), "export": self.core_export(&m.export.clone().map_index(EntityIndex::Memory))?,
                })),
                GlobalInitializer::ExtractRealloc(r) => inits.push(json!({
                    "op": "extract-realloc", "index": r.index.as_u32(), "def": self.core_def(&r.def)?,
                })),
                GlobalInitializer::ExtractPostReturn(p) => inits.push(json!({
                    "op": "extract-post-return", "index": p.index.as_u32(), "def": self.core_def(&p.def)?,
                })),
                GlobalInitializer::ExtractTable(t) => inits.push(json!({
                    "op": "extract-table", "index": t.index.as_u32(), "export": self.core_export(&t.export.clone().map_index(EntityIndex::Table))?,
                })),
                GlobalInitializer::ExtractCallback(c) => inits.push(json!({
                    "op": "extract-callback", "index": c.index.as_u32(), "def": self.core_def(&c.def)?,
                })),
                GlobalInitializer::Resource(r) => inits.push(json!({
                    "op": "resource",
                    "resource": self.component.resource_index(r.index).as_u32(),
                    "dtor": match &r.dtor { Some(d) => self.core_def(d)?, None => Value::Null },
                    "instance": r.instance.as_u32(),
                })),
            }
        }

        let mut trampolines = Vec::new();
        for (idx, t) in translation.trampolines.iter() {
            let mut j = match t {
                Trampoline::LowerImport { index, lower_ty, options } => json!({
                    "kind": "lower-import",
                    "import": lowered_to_import.get(&index.as_u32()).copied()
                        .ok_or_else(|| anyhow!("lowered function {} has no import", index.as_u32()))?,
                    "type": self.func_type(*lower_ty),
                    "options": options.as_u32(),
                }),
                Trampoline::ResourceNew { ty, .. } | Trampoline::ResourceRep { ty, .. } | Trampoline::ResourceDrop { ty, .. } => {
                    self.tables.borrow_mut().insert(ty.as_u32());
                    let kind = match t {
                        Trampoline::ResourceNew { .. } => "resource-new",
                        Trampoline::ResourceRep { .. } => "resource-rep",
                        _ => "resource-drop",
                    };
                    json!({ "kind": kind, "table": ty.as_u32() })
                }
                // Raised by fused adapters, e.g. when a string copied between two child
                // components is not valid in its encoding (#47).
                Trampoline::Trap => json!({ "kind": "trap", "messages": trap_messages() }),
                // Copies a string between two child components' memories (#47).
                Trampoline::Transcoder { op, from, from64: false, to, to64: false } => json!({
                    "kind": "transcode", "op": op.desc(), "from": from.as_u32(), "to": to.as_u32(),
                }),
                other => self.async_trampoline(other).unwrap_or_else(|| json!({ "kind": "unsupported", "detail": format!("{other:?}") })),
            };
            j.as_object_mut().unwrap().insert("index".into(), json!(idx.as_u32()));
            trampolines.push(j);
        }

        let mut options: Vec<Value> = self
            .component
            .options
            .iter()
            .map(|(idx, o)| {
                let (memory, realloc) = match o.data_model {
                    CanonicalOptionsDataModel::LinearMemory(lm) => (lm.memory.map(|m| m.as_u32()), lm.realloc.map(|r| r.as_u32())),
                    CanonicalOptionsDataModel::Gc {} => (None, None),
                };
                json!({
                    "index": idx.as_u32(),
                    "instance": o.instance.as_u32(),
                    "stringEncoding": match o.string_encoding {
                        StringEncoding::Utf8 => "utf8", StringEncoding::Utf16 => "utf16", StringEncoding::CompactUtf16 => "latin1+utf16",
                    },
                    "memory": memory,
                    "realloc": realloc,
                    "postReturn": o.post_return.map(|p| p.as_u32()),
                    "async": o.async_,
                    "callback": o.callback.map(|c| c.as_u32()),
                    "cancellable": o.cancellable,
                })
            })
            .collect();

        let imports: Vec<Value> = self
            .component
            .imports
            .iter()
            .map(|(idx, (import_idx, path))| {
                let (name, _) = &self.component.import_types[*import_idx];
                json!({ "index": idx.as_u32(), "name": name, "path": path })
            })
            .collect();

        let mut exports = Vec::new();
        for (name, export_idx) in sorted_exports(self.component.exports.raw_iter()) {
            self.collect_export(None, &name, export_idx, &mut exports)?;
        }

        // Fused adapters name stream, future and error-context tables by index at runtime
        // (`stream-transfer` etc.), so describe all of them, not only those the plan references.
        // ComponentTypes has no public way to count them; its serialized form does.
        if translation.trampolines.iter().any(|(_, t)| is_transfer(t)) {
            let all = serde_json::to_value(self.types)?;
            // A PrimaryMap serializes as `{"elems": [...]}`.
            let count = |key: &str| all.get(key).and_then(|m| m.get("elems")).and_then(Value::as_array).map_or(0, |a| a.len() as u32);
            self.stream_tables.borrow_mut().extend(0..count("stream_tables"));
            self.future_tables.borrow_mut().extend(0..count("future_tables"));
            self.error_context_tables.borrow_mut().extend(0..count("error_context_tables"));
        }
        // Describing an element type can reference more tables (`stream<future<T>>`).
        let (stream_tables, future_tables) = self.transmit_tables();
        // ComponentTypes has no public iterator over resource tables, so we describe exactly the
        // tables referenced by types and trampolines (after the stream and future element types).
        let resource_tables = self.resource_tables();
        let error_context_tables: Vec<Value> = self
            .error_context_tables
            .borrow()
            .iter()
            .map(|&i| json!({ "index": i, "instance": self.types[TypeComponentLocalErrorContextTableIndex::from_u32(i)].instance.as_u32() }))
            .collect();

        renumber_options(&mut options, &mut exports, &mut trampolines);

        let plan = json!({
            "version": PLAN_VERSION,
            "modules": module_json,
            "initializers": inits,
            "trampolines": trampolines,
            "options": options,
            "imports": imports,
            "resourceTables": resource_tables,
            "streamTables": stream_tables,
            "futureTables": future_tables,
            "errorContextTables": error_context_tables,
            "exports": exports,
        });
        Ok((plan, files))
    }

    /// The stream and future tables referenced in the plan: `{index, instance, element}`.
    fn transmit_tables(&self) -> (Vec<Value>, Vec<Value>) {
        let mut streams = std::collections::BTreeMap::new();
        let mut futures = std::collections::BTreeMap::new();
        loop {
            let pending_streams: Vec<u32> = self.stream_tables.borrow().iter().copied().filter(|i| !streams.contains_key(i)).collect();
            let pending_futures: Vec<u32> = self.future_tables.borrow().iter().copied().filter(|i| !futures.contains_key(i)).collect();
            if pending_streams.is_empty() && pending_futures.is_empty() {
                break;
            }
            for i in pending_streams {
                let t = &self.types[TypeStreamTableIndex::from_u32(i)];
                let element = self.types[t.ty].payload.as_ref().map(|p| self.ty(p));
                streams.insert(i, json!({ "index": i, "instance": t.instance.as_u32(), "element": element }));
            }
            for i in pending_futures {
                let t = &self.types[TypeFutureTableIndex::from_u32(i)];
                let element = self.types[t.ty].payload.as_ref().map(|p| self.ty(p));
                futures.insert(i, json!({ "index": i, "instance": t.instance.as_u32(), "element": element }));
            }
        }
        (streams.into_values().collect(), futures.into_values().collect())
    }

    /// The async built-ins and the fused adapters' async intrinsics (plan v5, #16).
    fn async_trampoline(&self, t: &Trampoline) -> Option<Value> {
        let stream = |kind: &str, ty: &TypeStreamTableIndex| {
            self.stream_tables.borrow_mut().insert(ty.as_u32());
            json!({ "kind": kind, "table": ty.as_u32() })
        };
        let future = |kind: &str, ty: &TypeFutureTableIndex| {
            self.future_tables.borrow_mut().insert(ty.as_u32());
            json!({ "kind": kind, "table": ty.as_u32() })
        };
        let with = |mut j: Value, key: &str, v: Value| {
            j.as_object_mut().unwrap().insert(key.into(), v);
            j
        };
        let simple = |kind: &str, instance: &RuntimeComponentInstanceIndex| json!({ "kind": kind, "instance": instance.as_u32() });
        Some(match t {
            Trampoline::BackpressureInc { instance } => simple("backpressure-inc", instance),
            Trampoline::BackpressureDec { instance } => simple("backpressure-dec", instance),
            Trampoline::TaskReturn { instance, results, options } => json!({
                "kind": "task-return", "instance": instance.as_u32(),
                "results": self.types[*results].types.iter().map(|t| self.ty(t)).collect::<Vec<_>>(),
                // The fused adapters name a task's result type by this index (`prepare-call`).
                "resultsIndex": results.as_u32(),
                "options": options.as_u32(),
            }),
            Trampoline::TaskCancel { instance } => simple("task-cancel", instance),
            Trampoline::WaitableSetNew { instance } => simple("waitable-set-new", instance),
            Trampoline::WaitableSetWait { instance, options } => with(simple("waitable-set-wait", instance), "options", json!(options.as_u32())),
            Trampoline::WaitableSetPoll { instance, options } => with(simple("waitable-set-poll", instance), "options", json!(options.as_u32())),
            Trampoline::WaitableSetDrop { instance } => simple("waitable-set-drop", instance),
            Trampoline::WaitableJoin { instance } => simple("waitable-join", instance),
            Trampoline::SubtaskDrop { instance } => simple("subtask-drop", instance),
            Trampoline::SubtaskCancel { instance, async_ } => with(simple("subtask-cancel", instance), "async", json!(async_)),
            Trampoline::StreamNew { ty, .. } => stream("stream-new", ty),
            Trampoline::StreamRead { ty, options, .. } => with(stream("stream-read", ty), "options", json!(options.as_u32())),
            Trampoline::StreamWrite { ty, options, .. } => with(stream("stream-write", ty), "options", json!(options.as_u32())),
            Trampoline::StreamCancelRead { ty, async_, .. } => with(stream("stream-cancel-read", ty), "async", json!(async_)),
            Trampoline::StreamCancelWrite { ty, async_, .. } => with(stream("stream-cancel-write", ty), "async", json!(async_)),
            Trampoline::StreamDropReadable { ty, .. } => stream("stream-drop-readable", ty),
            Trampoline::StreamDropWritable { ty, .. } => stream("stream-drop-writable", ty),
            Trampoline::FutureNew { ty, .. } => future("future-new", ty),
            Trampoline::FutureRead { ty, options, .. } => with(future("future-read", ty), "options", json!(options.as_u32())),
            Trampoline::FutureWrite { ty, options, .. } => with(future("future-write", ty), "options", json!(options.as_u32())),
            Trampoline::FutureCancelRead { ty, async_, .. } => with(future("future-cancel-read", ty), "async", json!(async_)),
            Trampoline::FutureCancelWrite { ty, async_, .. } => with(future("future-cancel-write", ty), "async", json!(async_)),
            Trampoline::FutureDropReadable { ty, .. } => future("future-drop-readable", ty),
            Trampoline::FutureDropWritable { ty, .. } => future("future-drop-writable", ty),
            Trampoline::ErrorContextNew { instance, options, ty } => {
                self.error_context_tables.borrow_mut().insert(ty.as_u32());
                with(simple("error-context-new", instance), "options", json!(options.as_u32()))
            }
            Trampoline::ErrorContextDebugMessage { instance, options, ty } => {
                self.error_context_tables.borrow_mut().insert(ty.as_u32());
                with(simple("error-context-debug-message", instance), "options", json!(options.as_u32()))
            }
            Trampoline::ErrorContextDrop { instance, ty } => {
                self.error_context_tables.borrow_mut().insert(ty.as_u32());
                simple("error-context-drop", instance)
            }
            // Fused adapters move resources between child components (#70).
            Trampoline::ResourceTransferOwn => json!({ "kind": "resource-transfer-own" }),
            Trampoline::ResourceTransferBorrow => json!({ "kind": "resource-transfer-borrow" }),
            Trampoline::PrepareCall { memory } => json!({ "kind": "prepare-call", "memory": memory.map(|m| m.as_u32()) }),
            Trampoline::SyncStartCall { callback } => json!({ "kind": "sync-start-call", "callback": callback.map(|c| c.as_u32()) }),
            Trampoline::AsyncStartCall { callback, post_return } => json!({
                "kind": "async-start-call", "callback": callback.map(|c| c.as_u32()), "postReturn": post_return.map(|p| p.as_u32()),
            }),
            Trampoline::FutureTransfer => json!({ "kind": "future-transfer" }),
            Trampoline::StreamTransfer => json!({ "kind": "stream-transfer" }),
            Trampoline::ErrorContextTransfer => json!({ "kind": "error-context-transfer" }),
            Trampoline::EnterSyncCall => json!({ "kind": "enter-sync-call" }),
            Trampoline::ExitSyncCall => json!({ "kind": "exit-sync-call" }),
            Trampoline::ThreadIndex { instance } => simple("thread-index", instance),
            Trampoline::ThreadNewIndirect { instance, start_func_table_idx, .. } => {
                with(simple("thread-new-indirect", instance), "table", json!(start_func_table_idx.as_u32()))
            }
            Trampoline::ThreadResumeLater { instance } => simple("thread-resume-later", instance),
            Trampoline::ThreadSuspend { instance, cancellable } => with(simple("thread-suspend", instance), "cancellable", json!(cancellable)),
            Trampoline::ThreadYield { instance, cancellable } => with(simple("thread-yield", instance), "cancellable", json!(cancellable)),
            Trampoline::ThreadSuspendThenResume { instance, cancellable } => {
                with(simple("thread-suspend-then-resume", instance), "cancellable", json!(cancellable))
            }
            Trampoline::ThreadYieldThenResume { instance, cancellable } => {
                with(simple("thread-yield-then-resume", instance), "cancellable", json!(cancellable))
            }
            Trampoline::ThreadSuspendThenPromote { instance, cancellable } => {
                with(simple("thread-suspend-then-promote", instance), "cancellable", json!(cancellable))
            }
            Trampoline::ThreadYieldThenPromote { instance, cancellable } => {
                with(simple("thread-yield-then-promote", instance), "cancellable", json!(cancellable))
            }
            _ => return None,
        })
    }

    fn resource_tables(&self) -> Vec<Value> {
        self
            .tables
            .borrow()
            .iter()
            .map(|&i| {
                let idx = TypeResourceTableIndex::from_u32(i);
                match &self.types[idx] {
                    TypeResourceTable::Concrete { .. } => {
                        let t = &self.types[idx];
                        let res = t.unwrap_concrete_ty();
                        json!({
                            "index": i, "resource": res.as_u32(), "instance": t.unwrap_concrete_instance().as_u32(),
                            "imported": self.component.defined_resource_index(res).is_none(),
                            // For imported (host-defined) resources: the runtime import naming it.
                            "import": self.component.imported_resources.get(res).map(|i| i.as_u32()),
                        })
                    }
                    TypeResourceTable::Abstract(_) => json!({ "index": i, "abstract": true }),
                }
            })
            .collect()
    }

    fn collect_export(&self, instance: Option<&str>, name: &str, idx: ExportIndex, out: &mut Vec<Value>) -> Result<()> {
        match &self.component.export_items[idx] {
            Export::LiftedFunction { ty, func, options } => out.push(json!({
                "instance": instance,
                "name": name,
                "func": self.core_def(func)?,
                "options": options.as_u32(),
                "type": self.func_type(*ty),
            })),
            Export::Instance { exports, .. } => {
                if instance.is_some() {
                    bail!("nested exported instances are not supported ({name})");
                }
                for (inner, inner_idx) in sorted_exports(exports.raw_iter()) {
                    self.collect_export(Some(name), &inner, inner_idx, out)?;
                }
            }
            // Types and modules carry no runtime behaviour for a host binding.
            Export::Type(_) | Export::ModuleStatic { .. } | Export::ModuleImport { .. } => {}
        }
        Ok(())
    }

    fn core_def(&self, def: &CoreDef) -> Result<Value> {
        Ok(match def {
            CoreDef::Export(e) => {
                let mut j = self.core_export(e)?;
                j.as_object_mut().unwrap().insert("kind".into(), json!("export"));
                j
            }
            CoreDef::Trampoline(t) => json!({ "kind": "trampoline", "index": t.as_u32() }),
            CoreDef::InstanceFlags(i) => json!({ "kind": "instance-flags", "index": i.as_u32() }),
            // The fused adapters' access to the current thread's `context.get`/`context.set` slots
            // (plan v5); other unsafe intrinsics are for Wasmtime's own compile-time builtins.
            CoreDef::UnsafeIntrinsic(u) if u.name().starts_with("context-") => json!({ "kind": "intrinsic", "name": u.name() }),
            CoreDef::UnsafeIntrinsic(u) => bail!("unsafe intrinsic {u:?} is not supported"),
            // The per-task "may block" flag (an i32 global). Wasmtime 48's fused adapters import
            // it even for synchronous component-to-component calls.
            CoreDef::TaskMayBlock => json!({ "kind": "task-may-block" }),
        })
    }

    /// Resolves an export to `{instance, name}`; index-based references are
    /// mapped back to the module's export name since Chicory links by name.
    fn core_export(&self, e: &CoreExport<EntityIndex>) -> Result<Value> {
        let name = match &e.item {
            ExportItem::Name(n) => n.clone(),
            ExportItem::Index(entity) => {
                let midx = *self
                    .instance_modules
                    .get(e.instance.as_u32() as usize)
                    .ok_or_else(|| anyhow!("reference to instance {} before it is created", e.instance.as_u32()))?;
                let module = &self.modules[midx].module;
                module
                    .exports
                    .iter()
                    .find(|(_, idx)| *idx == entity)
                    .map(|(atom, _)| module.strings[*atom].to_string())
                    .ok_or_else(|| anyhow!("core module {} does not export {entity:?}", midx.as_u32()))?
            }
        };
        let mut m = Map::new();
        m.insert("instance".into(), json!(e.instance.as_u32()));
        m.insert("name".into(), json!(name));
        Ok(Value::Object(m))
    }

    fn func_type(&self, ty: TypeFuncIndex) -> Value {
        let f = &self.types[ty];
        let params: Vec<Value> = self.types[f.params]
            .types
            .iter()
            .zip(f.param_names.iter())
            .map(|(t, n)| json!({ "name": n, "type": self.ty(t) }))
            .collect();
        let results: Vec<Value> = self.types[f.results].types.iter().map(|t| self.ty(t)).collect();
        json!({ "params": params, "results": results, "async": f.async_ })
    }

    /// Self-contained type descriptor: everything the canonical ABI needs
    /// (shape, field/case names) without referring back to type tables.
    fn ty(&self, t: &InterfaceType) -> Value {
        let k = |s: &str| json!({ "k": s });
        match t {
            InterfaceType::Bool => k("bool"),
            InterfaceType::S8 => k("s8"),
            InterfaceType::U8 => k("u8"),
            InterfaceType::S16 => k("s16"),
            InterfaceType::U16 => k("u16"),
            InterfaceType::S32 => k("s32"),
            InterfaceType::U32 => k("u32"),
            InterfaceType::S64 => k("s64"),
            InterfaceType::U64 => k("u64"),
            InterfaceType::Float32 => k("f32"),
            InterfaceType::Float64 => k("f64"),
            InterfaceType::Char => k("char"),
            InterfaceType::String => k("string"),
            InterfaceType::Record(i) => json!({
                "k": "record",
                "fields": self.types[*i].fields.iter().map(|f| json!({ "name": f.name, "type": self.ty(&f.ty) })).collect::<Vec<_>>(),
            }),
            InterfaceType::Variant(i) => json!({
                "k": "variant",
                "cases": self.types[*i].cases.iter().map(|(n, t)| json!({ "name": n, "type": t.as_ref().map(|t| self.ty(t)) })).collect::<Vec<_>>(),
            }),
            InterfaceType::List(i) => json!({ "k": "list", "element": self.ty(&self.types[*i].element) }),
            InterfaceType::Tuple(i) => json!({ "k": "tuple", "types": self.types[*i].types.iter().map(|t| self.ty(t)).collect::<Vec<_>>() }),
            InterfaceType::Flags(i) => json!({ "k": "flags", "names": self.types[*i].names.iter().collect::<Vec<_>>() }),
            InterfaceType::Enum(i) => json!({ "k": "enum", "names": self.types[*i].names.iter().collect::<Vec<_>>() }),
            InterfaceType::Option(i) => json!({ "k": "option", "type": self.ty(&self.types[*i].ty) }),
            InterfaceType::Result(i) => {
                let r = &self.types[*i];
                json!({ "k": "result", "ok": r.ok.as_ref().map(|t| self.ty(t)), "err": r.err.as_ref().map(|t| self.ty(t)) })
            }
            InterfaceType::Own(i) => {
                self.tables.borrow_mut().insert(i.as_u32());
                json!({ "k": "own", "table": i.as_u32() })
            }
            InterfaceType::Borrow(i) => {
                self.tables.borrow_mut().insert(i.as_u32());
                json!({ "k": "borrow", "table": i.as_u32() })
            }
            InterfaceType::Stream(i) => {
                self.stream_tables.borrow_mut().insert(i.as_u32());
                json!({ "k": "stream", "table": i.as_u32() })
            }
            InterfaceType::Future(i) => {
                self.future_tables.borrow_mut().insert(i.as_u32());
                json!({ "k": "future", "table": i.as_u32() })
            }
            InterfaceType::ErrorContext(i) => {
                self.error_context_tables.borrow_mut().insert(i.as_u32());
                json!({ "k": "error-context", "table": i.as_u32() })
            }
            other => json!({ "k": "unsupported", "detail": format!("{other:?}") }),
        }
    }
}

fn is_transfer(t: &Trampoline) -> bool {
    matches!(t, Trampoline::StreamTransfer | Trampoline::FutureTransfer | Trampoline::ErrorContextTransfer)
}

fn entity_kind(ty: &EntityType) -> &'static str {
    match ty {
        EntityType::Function(_) => "func",
        EntityType::Table(_) => "table",
        EntityType::Memory(_) => "memory",
        EntityType::Global(_) => "global",
        EntityType::Tag(_) => "tag",
    }
}

/// Export names with their indices, sorted by name. Name maps iterate in hash order, and plan.json
/// must be deterministic (CI checks the committed fixtures; Gradle caches the decompose output).
fn sorted_exports<'a, K, T>(entries: impl Iterator<Item = (&'a K, &'a (ExportIndex, T))>) -> Vec<(String, ExportIndex)>
where
    K: ToString + ?Sized + 'a,
    T: 'a,
{
    let mut sorted: Vec<(String, ExportIndex)> = entries.map(|(name, (idx, _))| (name.to_string(), *idx)).collect();
    sorted.sort_by(|a, b| a.0.cmp(&b.0));
    sorted
}

/// Renumbers canonical options by first use: sorted exports, then trampolines, then any unused
/// ones in their original order. wasmtime-environ numbers options in translation order, which for
/// some components (e.g. an exported instance bundled from lifted functions) follows hash order,
/// so the raw indices would make plan.json differ between runs.
fn renumber_options(options: &mut [Value], exports: &mut [Value], trampolines: &mut [Value]) {
    let mut map: HashMap<u64, u64> = HashMap::new();
    let assign = |old: u64, map: &mut HashMap<u64, u64>| {
        let next = map.len() as u64;
        *map.entry(old).or_insert(next)
    };
    for v in exports.iter().chain(trampolines.iter()) {
        if let Some(old) = v.get("options").and_then(Value::as_u64) {
            assign(old, &mut map);
        }
    }
    for o in options.iter() {
        if let Some(old) = o.get("index").and_then(Value::as_u64) {
            assign(old, &mut map);
        }
    }
    for v in exports.iter_mut().chain(trampolines.iter_mut()) {
        if let Some(old) = v.get("options").and_then(Value::as_u64) {
            v["options"] = json!(map[&old]);
        }
    }
    for o in options.iter_mut() {
        if let Some(old) = o.get("index").and_then(Value::as_u64) {
            o["index"] = json!(map[&old]);
        }
    }
    options.sort_by_key(|o| o.get("index").and_then(Value::as_u64));
}

/// The message of every trap code a fused adapter can raise through the `trap` trampoline, indexed
/// by code (`null` for unused codes), so the runtime can report it like Wasmtime does.
fn trap_messages() -> Value {
    let codes: Vec<Value> = (0..=u8::MAX)
        .map(|code| wasmtime_environ::Trap::from_u8(code).map_or(Value::Null, |t| json!(t.to_string())))
        .collect();
    let used = codes.iter().rposition(|m| !m.is_null()).map_or(0, |i| i + 1);
    Value::Array(codes[..used].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../fixtures").join(name);
        std::fs::read(&path).unwrap_or_else(|e| panic!("reading {}: {e}", path.display()))
    }

    /// plan.json must not depend on hash iteration order (committed fixtures, Gradle build cache).
    #[test]
    fn plans_are_deterministic() {
        for name in ["res-edge.wasm", "roundtrip.wasm", "host-res.wasm", "types.wasm", "composed-str.wasm", "async-stream.wasm"] {
            let bytes = fixture(name);
            let first = decompose(&bytes).unwrap().0;
            for _ in 0..5 {
                assert_eq!(first, decompose(&bytes).unwrap().0, "{name}: plan changed between runs");
            }
        }
    }

    /// Child components with their own memories passing strings and lists: the fused adapters
    /// need multi-memory and transcoders, and used to panic the decomposer (#47).
    #[test]
    fn composed_components_with_multi_memory_adapters_decompose() {
        let (plan, modules) = decompose(&fixture("composed-str.wasm")).unwrap();
        let trampolines = plan["trampolines"].as_array().unwrap();
        let kinds: BTreeSet<&str> = trampolines.iter().map(|t| t["kind"].as_str().unwrap()).collect();
        assert_eq!(kinds, BTreeSet::from(["trap", "transcode"]), "{trampolines:?}");
        let ops: BTreeSet<&str> = trampolines.iter().filter_map(|t| t["op"].as_str()).collect();
        assert_eq!(ops.len(), 12, "every transcode op should be exercised: {ops:?}");
        // The adapter module imports both children's memories.
        let memories = |m: &Value| m["imports"].as_array().unwrap().iter().filter(|i| i["kind"] == "memory").count();
        assert!(plan["modules"].as_array().unwrap().iter().any(|m| memories(m) == 2));
        assert_eq!(modules.len(), plan["modules"].as_array().unwrap().len());
    }

    /// An async component (#16): its callbacks, async built-ins and stream / future tables are in the plan.
    #[test]
    fn async_components_decompose() {
        assert!(uses_async(&fixture("async-stream.wasm")).unwrap());
        assert!(!uses_async(&fixture("composed-str.wasm")).unwrap());
        let (plan, _) = decompose(&fixture("async-stream.wasm")).unwrap();
        let kinds: BTreeSet<&str> = plan["trampolines"].as_array().unwrap().iter().map(|t| t["kind"].as_str().unwrap()).collect();
        for k in ["task-return", "stream-new", "stream-write", "future-read", "waitable-set-new", "waitable-join"] {
            assert!(kinds.contains(k), "{k} missing from {kinds:?}");
        }
        assert!(!kinds.contains("unsupported"), "{kinds:?}");
        let callbacks = plan["initializers"].as_array().unwrap().iter().filter(|i| i["op"] == "extract-callback").count();
        assert_eq!(callbacks, 3);
        assert_eq!(plan["streamTables"].as_array().unwrap().len(), 1);
        assert_eq!(plan["futureTables"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn options_are_numbered_by_first_use() {
        let mut options = vec![json!({ "index": 0 }), json!({ "index": 1 }), json!({ "index": 2 })];
        let mut exports = vec![json!({ "name": "a", "options": 2 }), json!({ "name": "b", "options": 0 })];
        let mut trampolines = vec![json!({ "index": 0, "options": 2 })];
        renumber_options(&mut options, &mut exports, &mut trampolines);
        assert_eq!(exports[0]["options"], 0);
        assert_eq!(exports[1]["options"], 1);
        assert_eq!(trampolines[0]["options"], 0);
        let indices: Vec<_> = options.iter().map(|o| o["index"].as_u64().unwrap()).collect();
        assert_eq!(indices, vec![0, 1, 2]);
    }

    #[test]
    fn exports_are_sorted_by_name() {
        let entries = [("b".to_string(), (ExportIndex::from_u32(1), ())), ("a".to_string(), (ExportIndex::from_u32(0), ()))];
        let sorted = sorted_exports(entries.iter().map(|(k, v)| (k, v)));
        assert_eq!(sorted.iter().map(|(n, _)| n.as_str()).collect::<Vec<_>>(), vec!["a", "b"]);
    }

    #[test]
    fn task_return_rejects_borrow_and_accepts_own() {
        fn component_with_types_and_task_return(type_entries: &[&[u8]], result_type_idx: u8) -> Vec<u8> {
            fn push_section(out: &mut Vec<u8>, id: u8, payload: &[u8]) {
                out.push(id);
                let mut n = payload.len();
                loop {
                    let b = (n & 0x7f) as u8;
                    n >>= 7;
                    out.push(if n == 0 { b } else { b | 0x80 });
                    if n == 0 {
                        break;
                    }
                }
                out.extend_from_slice(payload);
            }
            let mut out = vec![0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00];
            // Section 1 (Core Module): exports `(memory 1)` as `"m"` for `task.return` memory option.
            push_section(
                &mut out,
                1,
                &[
                    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                    0x05, 0x03, 0x01, 0x00, 0x01,
                    0x07, 0x05, 0x01, 0x01, b'm', 0x02, 0x00,
                ],
            );
            // Section 2 (Core Instance): instantiate module 0.
            push_section(&mut out, 2, &[0x01, 0x00, 0x00, 0x00]);
            // Section 6 (Alias): core export memory `"m"` from core instance 0 as core memory 0.
            push_section(&mut out, 6, &[0x01, 0x00, 0x02, 0x01, 0x00, 0x01, b'm']);
            let mut type_sec = vec![type_entries.len() as u8];
            for entry in type_entries {
                type_sec.extend_from_slice(entry);
            }
            push_section(&mut out, 7, &type_sec);
            // Section 8 (Canonical): count=1, TaskReturn(0x09), result=Some(0x00) Type(idx), 1 option Memory(0).
            push_section(&mut out, 8, &[0x01, 0x09, 0x00, result_type_idx, 0x01, 0x03, 0x00]);
            out
        }

        // type 0: resource (rep i32); type 1: borrow<0>
        let direct_borrow = component_with_types_and_task_return(&[&[0x3f, 0x7f, 0x00], &[0x68, 0x00]], 1);
        let err = decompose(&direct_borrow).unwrap_err();
        assert!(err.to_string().contains("`task.return` result cannot contain a `borrow` type"), "got {err}");

        // Wrapped borrow types: tuple, record, variant, list, option, result, future, and stream.
        let nested_cases: [(&str, &[u8]); 8] = [
            ("tuple", &[0x6f, 0x02, 0x79, 0x01]),
            ("record", &[0x72, 0x01, 0x01, b'a', 0x01]),
            ("variant", &[0x71, 0x01, 0x01, b'c', 0x01, 0x01, 0x00]),
            ("list", &[0x70, 0x01]),
            ("option", &[0x6b, 0x01]),
            ("result", &[0x6a, 0x00, 0x01, 0x01]),
            ("future", &[0x65, 0x01, 0x01]),
            ("stream", &[0x66, 0x01, 0x01]),
        ];
        for (label, entry) in nested_cases {
            let bytes = component_with_types_and_task_return(&[&[0x3f, 0x7f, 0x00], &[0x68, 0x00], entry], 2);
            let err_nested = decompose(&bytes).unwrap_err();
            assert!(
                err_nested.to_string().contains("`task.return` result cannot contain a `borrow` type"),
                "{label}: got {err_nested}",
            );
        }

        // type 0: resource (rep i32); type 1: own<0>
        let direct_own = component_with_types_and_task_return(&[&[0x3f, 0x7f, 0x00], &[0x69, 0x00]], 1);
        assert!(decompose(&direct_own).is_ok());
    }
}

