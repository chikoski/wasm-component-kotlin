package wasm2kt.runtime.chicory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-memory form of the `plan.json` written by `wasm2kt-decompose`: everything needed to
 * instantiate a component's core modules on Chicory and to lift/lower values at its boundary.
 */
class ComponentPlan(
    val modules: List<PlanModule>,
    val initializers: List<Initializer>,
    val trampolines: Map<Int, Trampoline>,
    val options: Map<Int, CanonOptions>,
    val imports: Map<Int, PlanImport>,
    val resourceTables: Map<Int, ResourceTableInfo>,
    val exports: List<PlanExport>,
) {
    companion object {
        const val SUPPORTED_VERSION = 4

        fun parse(json: String): ComponentPlan = fromJson(Json.parseToJsonElement(json).jsonObject)

        fun fromJson(root: JsonObject): ComponentPlan {
            val version = root.int("version")
            require(version == SUPPORTED_VERSION) {
                "Unsupported plan version $version (expected $SUPPORTED_VERSION); regenerate it with a matching wasm2kt-decompose"
            }
            return ComponentPlan(
                modules = root.array("modules").map { m ->
                    val o = m.jsonObject
                    PlanModule(
                        index = o.int("index"),
                        file = o.str("file"),
                        imports = o.array("imports").map { i ->
                            val io = i.jsonObject
                            PlanModuleImport(io.str("module"), io.str("name"), io.str("kind"))
                        },
                    )
                },
                initializers = root.array("initializers").map { parseInitializer(it.jsonObject) },
                trampolines = root.array("trampolines").associate { t ->
                    val o = t.jsonObject
                    o.int("index") to parseTrampoline(o)
                },
                options = root.array("options").associate { opt ->
                    val o = opt.jsonObject
                    o.int("index") to CanonOptions(
                        stringEncoding = StringEncoding.fromPlan(o.str("stringEncoding")),
                        memory = o.optInt("memory"),
                        realloc = o.optInt("realloc"),
                        postReturn = o.optInt("postReturn"),
                        async = o["async"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
                imports = root.array("imports").associate { imp ->
                    val o = imp.jsonObject
                    o.int("index") to PlanImport(o.str("name"), o.array("path").map { it.jsonPrimitive.content })
                },
                resourceTables = root.array("resourceTables").associate { rt ->
                    val o = rt.jsonObject
                    o.int("index") to ResourceTableInfo(
                        index = o.int("index"),
                        resource = o.optInt("resource"),
                        imported = o["imported"]?.jsonPrimitive?.boolean ?: false,
                        abstract = o["abstract"]?.jsonPrimitive?.boolean ?: false,
                        import = o.optInt("import"),
                    )
                },
                exports = root.array("exports").map { e ->
                    val o = e.jsonObject
                    PlanExport(
                        instance = o["instance"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                        name = o.str("name"),
                        func = parseDef(o.getValue("func").jsonObject),
                        options = o.int("options"),
                        type = FuncType.fromJson(o.getValue("type").jsonObject),
                    )
                },
            )
        }

        private fun parseInitializer(o: JsonObject): Initializer = when (val op = o.str("op")) {
            "instantiate" -> Initializer.Instantiate(
                instance = o.int("instance"),
                module = o.int("module"),
                args = o.array("args").map { a ->
                    val ao = a.jsonObject
                    InstantiateArg(ao.str("module"), ao.str("name"), parseDef(ao.getValue("def").jsonObject))
                },
            )
            "extract-memory" -> Initializer.ExtractMemory(o.int("index"), parseExport(o.getValue("export").jsonObject))
            "extract-realloc" -> Initializer.ExtractRealloc(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "extract-post-return" -> Initializer.ExtractPostReturn(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "extract-table" -> Initializer.ExtractTable(o.int("index"), parseExport(o.getValue("export").jsonObject))
            "resource" -> Initializer.Resource(
                resource = o.int("resource"),
                dtor = o["dtor"]?.takeUnless { it is JsonNull }?.let { parseDef(it.jsonObject) },
            )
            else -> error("Unknown plan initializer '$op'")
        }

        private fun parseTrampoline(o: JsonObject): Trampoline = when (val kind = o.str("kind")) {
            "lower-import" -> Trampoline.LowerImport(
                import = o.int("import"),
                type = FuncType.fromJson(o.getValue("type").jsonObject),
                options = o.int("options"),
            )
            "resource-new" -> Trampoline.ResourceNew(o.int("table"))
            "resource-rep" -> Trampoline.ResourceRep(o.int("table"))
            "resource-drop" -> Trampoline.ResourceDrop(o.int("table"))
            "trap" -> Trampoline.Trap(o.array("messages").map { (it as? JsonPrimitive)?.takeUnless { m -> m is JsonNull }?.content })
            "transcode" -> {
                val op = o.str("op")
                TranscodeOp.fromPlan(op)?.let { Trampoline.Transcode(it, o.int("from"), o.int("to")) }
                    ?: Trampoline.Unsupported("transcode $op")
            }
            else -> Trampoline.Unsupported(o["detail"]?.jsonPrimitive?.content ?: kind)
        }

        private fun parseExport(o: JsonObject) = CoreExportRef(o.int("instance"), o.str("name"))

        private fun parseDef(o: JsonObject): CoreDef = when (val kind = o.str("kind")) {
            "export" -> CoreDef.Export(parseExport(o))
            "trampoline" -> CoreDef.Trampoline(o.int("index"))
            "instance-flags" -> CoreDef.InstanceFlags(o.int("index"))
            "task-may-block" -> CoreDef.TaskMayBlock
            else -> error("Unknown core definition kind '$kind'")
        }
    }
}

class PlanModule(val index: Int, val file: String, val imports: List<PlanModuleImport>)

class PlanModuleImport(val module: String, val name: String, val kind: String)

class PlanImport(val name: String, val path: List<String>) {
    /**
     * Key passed to [wasm2kt.runtime.WitHostImportDispatcher.dispatchHostImport], matching the
     * Wasmtime bridge (ADR-0011): `name` for root functions, `<interface>#<function>` for interface
     * members, where `<interface>` is the import name as written in the component: fully qualified
     * (`pkg:ns/iface@1.0`) for package interfaces, the bare name for inline interfaces.
     */
    val dispatchKey: String
        get() = if (path.isEmpty()) name else "$name#${path.joinToString("#")}"

    /** Key for dropping a host resource this import names, matching the Wasmtime bridge. */
    val resourceDropKey: String
        get() = if (path.isEmpty()) "[resource-drop]$name" else "$name#[resource-drop]${path.last()}"
}

class PlanExport(
    val instance: String?,
    val name: String,
    val func: CoreDef,
    val options: Int,
    val type: FuncType,
)

class CanonOptions(
    val stringEncoding: StringEncoding,
    val memory: Int?,
    val realloc: Int?,
    val postReturn: Int?,
    val async: Boolean,
)

/** [import] names the runtime import of an imported (host-defined) resource. */
class ResourceTableInfo(val index: Int, val resource: Int?, val imported: Boolean, val abstract: Boolean, val import: Int? = null)

class CoreExportRef(val instance: Int, val name: String)

sealed interface CoreDef {
    data class Export(val ref: CoreExportRef) : CoreDef
    data class Trampoline(val index: Int) : CoreDef
    data class InstanceFlags(val index: Int) : CoreDef

    /** The current task's "may block" flag, an i32 global (plan v3; imported by fused adapters). */
    data object TaskMayBlock : CoreDef
}

class InstantiateArg(val module: String, val name: String, val def: CoreDef)

sealed interface Initializer {
    class Instantiate(val instance: Int, val module: Int, val args: List<InstantiateArg>) : Initializer
    class ExtractMemory(val index: Int, val export: CoreExportRef) : Initializer
    class ExtractRealloc(val index: Int, val def: CoreDef) : Initializer
    class ExtractPostReturn(val index: Int, val def: CoreDef) : Initializer
    class ExtractTable(val index: Int, val export: CoreExportRef) : Initializer
    class Resource(val resource: Int, val dtor: CoreDef?) : Initializer
}

sealed interface Trampoline {
    class LowerImport(val import: Int, val type: FuncType, val options: Int) : Trampoline
    class ResourceNew(val table: Int) : Trampoline
    class ResourceRep(val table: Int) : Trampoline
    class ResourceDrop(val table: Int) : Trampoline

    /** Raises the trap whose code the adapter passes; [messages] are indexed by code (plan v4). */
    class Trap(val messages: List<String?>) : Trampoline

    /** Copies a string between two memories of the component (plan v4; #47). */
    class Transcode(val op: TranscodeOp, val from: Int, val to: Int) : Trampoline
    class Unsupported(val detail: String) : Trampoline
}

/** A string transcode a fused adapter performs, named as in wasmtime-environ (`Transcode::desc`). */
enum class TranscodeOp(val desc: String) {
    UTF8_TO_UTF8("utf8-to-utf8"),
    UTF16_TO_UTF16("utf16-to-utf16"),
    LATIN1_TO_LATIN1("latin1-to-latin1"),
    LATIN1_TO_UTF16("latin1-to-utf16"),
    LATIN1_TO_UTF8("latin1-to-utf8"),
    UTF16_TO_COMPACT_PROBABLY_UTF16("utf16-to-compact-probably-utf16"),
    UTF16_TO_COMPACT_UTF16("utf16-to-compact-utf16"),
    UTF16_TO_LATIN1("utf16-to-latin1"),
    UTF16_TO_UTF8("utf16-to-utf8"),
    UTF8_TO_COMPACT_UTF16("utf8-to-compact-utf16"),
    UTF8_TO_LATIN1("utf8-to-latin1"),
    UTF8_TO_UTF16("utf8-to-utf16"),
    ;

    companion object {
        fun fromPlan(desc: String): TranscodeOp? = values().firstOrNull { it.desc == desc }
    }
}

enum class StringEncoding {
    UTF8, UTF16, LATIN1_UTF16;

    companion object {
        fun fromPlan(value: String): StringEncoding = when (value) {
            "utf8" -> UTF8
            "utf16" -> UTF16
            "latin1+utf16" -> LATIN1_UTF16
            else -> error("Unknown string encoding '$value'")
        }
    }
}

/** `pkg:ns/iface@1.0` -> `iface`; a bare name is returned as is. */
internal fun shortInterfaceName(qualified: String): String =
    qualified.substringAfter('/', qualified).substringBefore('@')

/** `pkg:ns/iface@1.0` -> `pkg:ns/iface`. */
internal fun unversionedInterfaceName(qualified: String): String = qualified.substringBefore('@')

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.optInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: emptyList<JsonElement>()
