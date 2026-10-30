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
//! Output: `<out-dir>/plan.json` and `<out-dir>/core<N>.wasm`.

use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Map, Value};
use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap};
use std::path::Path;
use wasmtime_environ::component::*;
use wasmtime_environ::wasmparser::{Validator, WasmFeatures};
use wasmtime_environ::{EntityIndex, EntityType, ModuleTranslation, PrimaryMap, ScopeVec, Tunables};

/// Bump whenever the plan.json schema changes incompatibly.
const PLAN_VERSION: u32 = 1;

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
    Ok(())
}

/// Returns the plan and the list of `(file name, core module bytes)`.
pub fn decompose(component_bytes: &[u8]) -> Result<(Value, Vec<(String, Vec<u8>)>)> {
    let scope = ScopeVec::new();
    let tunables = Tunables::default_u32();
    // Chicory executes plain core wasm; keep exceptions off so wasmtime's
    // generated adapters don't emit `try_table` barriers it can't run.
    let features = (WasmFeatures::WASM2 | WasmFeatures::COMPONENT_MODEL)
        .difference(WasmFeatures::EXCEPTIONS);
    let mut validator = Validator::new_with_features(features);
    let mut types = ComponentTypesBuilder::new(&validator);
    let (translation, modules) = Translator::new(&tunables, &mut validator, &mut types, &scope)
        .translate(component_bytes)
        .map_err(|e| anyhow!(e).context("failed to translate component"))?;
    let (types, _) = types.finish(&translation.component);

    let ctx = Ctx { component: &translation.component, types: &types, modules: &modules, instance_modules: Vec::new(), tables: RefCell::default() };
    ctx.build(&translation)
}

struct Ctx<'a, 'data> {
    component: &'a Component,
    types: &'a ComponentTypes,
    modules: &'a PrimaryMap<StaticModuleIndex, ModuleTranslation<'data>>,
    /// RuntimeInstanceIndex -> static module it instantiates (filled while walking initializers).
    instance_modules: Vec<StaticModuleIndex>,
    /// Resource tables referenced anywhere in the plan.
    tables: RefCell<BTreeSet<u32>>,
}

impl Ctx<'_, '_> {
    fn build(mut self, translation: &ComponentTranslation) -> Result<(Value, Vec<(String, Vec<u8>)>)> {
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
                GlobalInitializer::ExtractCallback(_) => bail!("async callbacks are not supported yet"),
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
                other => json!({ "kind": "unsupported", "detail": format!("{other:?}") }),
            };
            j.as_object_mut().unwrap().insert("index".into(), json!(idx.as_u32()));
            trampolines.push(j);
        }

        let options: Vec<Value> = self
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
                    "stringEncoding": match o.string_encoding {
                        StringEncoding::Utf8 => "utf8", StringEncoding::Utf16 => "utf16", StringEncoding::CompactUtf16 => "latin1+utf16",
                    },
                    "memory": memory,
                    "realloc": realloc,
                    "postReturn": o.post_return.map(|p| p.as_u32()),
                    "async": o.async_,
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
        for (name, (export_idx, _)) in self.component.exports.raw_iter() {
            self.collect_export(None, &name.to_string(), *export_idx, &mut exports)?;
        }

        // ComponentTypes has no public iterator over resource tables, so we
        // describe exactly the tables referenced by types and trampolines.
        let resource_tables: Vec<Value> = self
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
                        })
                    }
                    TypeResourceTable::Abstract(_) => json!({ "index": i, "abstract": true }),
                }
            })
            .collect();

        let plan = json!({
            "version": PLAN_VERSION,
            "modules": module_json,
            "initializers": inits,
            "trampolines": trampolines,
            "options": options,
            "imports": imports,
            "resourceTables": resource_tables,
            "exports": exports,
        });
        Ok((plan, files))
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
                for (inner, (inner_idx, _)) in exports.raw_iter() {
                    self.collect_export(Some(name), &inner.to_string(), *inner_idx, out)?;
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
            CoreDef::UnsafeIntrinsic(u) => bail!("unsafe intrinsic {u:?} is not supported"),
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
            other => json!({ "k": "unsupported", "detail": format!("{other:?}") }),
        }
    }
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
