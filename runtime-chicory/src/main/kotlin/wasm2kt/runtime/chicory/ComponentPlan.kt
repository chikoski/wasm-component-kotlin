@file:OptIn(InternalWasm2KtApi::class)

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
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.PlanAsyncRules
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.TranscodeOp
import wasm2kt.runtime.canonical.WitType

/**
 * In-memory form of the `plan.json` written by `wasm2kt-decompose`: everything needed to
 * instantiate a component's core modules on Chicory and to lift/lower values at its boundary.
 */
class ComponentPlan internal constructor(
    internal val modules: List<PlanModule>,
    internal val initializers: List<Initializer>,
    internal val trampolines: Map<Int, Trampoline>,
    internal val options: Map<Int, CanonOptions>,
    internal val imports: Map<Int, PlanImport>,
    internal val resourceTables: Map<Int, ResourceTableInfo>,
    internal val exports: List<PlanExport>,
    internal val streamTables: Map<Int, TransmitTableInfo> = emptyMap(),
    internal val futureTables: Map<Int, TransmitTableInfo> = emptyMap(),
    /** Error-context table -> its component instance (plan v5). */
    internal val errorContextTables: Map<Int, Int> = emptyMap(),
) {
    /**
     * Whether the component uses the async ABI (#16): then every call runs as a Component Model task
     * on the scheduler ([AsyncStore]); otherwise calls run directly on the caller's thread.
     */
    val usesAsync: Boolean by lazy {
        PlanAsyncRules.usesAsync(
            trampolineKinds = trampolines.values.asSequence().filterIsInstance<Trampoline.Async>().map { it.kind }.asIterable(),
            hasAsyncOptionOrCallback = options.values.any { it.async || it.callback != null },
            hasAsyncExport = exports.any { it.type.async },
            hasExtractCallback = initializers.any { it is Initializer.ExtractCallback },
        )
    }

    companion object {
        const val SUPPORTED_VERSION = 5

        fun parse(json: String): ComponentPlan = fromJson(Json.parseToJsonElement(json).jsonObject)

        internal fun fromJson(root: JsonObject): ComponentPlan {
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
                        instance = o.optInt("instance") ?: 0,
                        callback = o.optInt("callback"),
                        cancellable = o["cancellable"]?.jsonPrimitive?.boolean ?: false,
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
                        instance = o.optInt("instance") ?: 0,
                    )
                },
                streamTables = parseTransmitTables(root, "streamTables"),
                futureTables = parseTransmitTables(root, "futureTables"),
                errorContextTables = root.array("errorContextTables").associate { t ->
                    val o = t.jsonObject
                    o.int("index") to o.int("instance")
                },
                exports = root.array("exports").map { e ->
                    val o = e.jsonObject
                    PlanExport(
                        instance = o["instance"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                        name = o.str("name"),
                        func = parseDef(o.getValue("func").jsonObject),
                        options = o.int("options"),
                        type = funcTypeFromJson(o.getValue("type").jsonObject),
                    )
                },
            )
        }

        private fun parseTransmitTables(root: JsonObject, key: String): Map<Int, TransmitTableInfo> =
            root.array(key).associate { t ->
                val o = t.jsonObject
                o.int("index") to TransmitTableInfo(
                    index = o.int("index"),
                    instance = o.int("instance"),
                    element = o["element"]?.takeUnless { it is JsonNull }?.let { witTypeFromJson(it.jsonObject) },
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
            "extract-callback" -> Initializer.ExtractCallback(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "resource" -> Initializer.Resource(
                resource = o.int("resource"),
                dtor = o["dtor"]?.takeUnless { it is JsonNull }?.let { parseDef(it.jsonObject) },
                instance = o.optInt("instance") ?: 0,
            )
            else -> error("Unknown plan initializer '$op'")
        }

        private fun parseTrampoline(o: JsonObject): Trampoline = when (val kind = o.str("kind")) {
            "lower-import" -> Trampoline.LowerImport(
                import = o.int("import"),
                type = funcTypeFromJson(o.getValue("type").jsonObject),
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
            "unsupported" -> Trampoline.Unsupported(o["detail"]?.jsonPrimitive?.content ?: kind)
            else -> Trampoline.Async(
                kind = kind,
                instance = o.optInt("instance"),
                table = o.optInt("table"),
                options = o.optInt("options"),
                async = o["async"]?.jsonPrimitive?.boolean ?: false,
                cancellable = o["cancellable"]?.jsonPrimitive?.boolean ?: false,
                results = o["results"]?.jsonArray?.map { witTypeFromJson(it.jsonObject) },
                resultsIndex = o.optInt("resultsIndex"),
                memory = o.optInt("memory"),
                callback = o.optInt("callback"),
                postReturn = o.optInt("postReturn"),
            )
        }

        private fun parseExport(o: JsonObject) = CoreExportRef(o.int("instance"), o.str("name"))

        private fun parseDef(o: JsonObject): CoreDef = when (val kind = o.str("kind")) {
            "export" -> CoreDef.Export(parseExport(o))
            "trampoline" -> CoreDef.Trampoline(o.int("index"))
            "instance-flags" -> CoreDef.InstanceFlags(o.int("index"))
            "task-may-block" -> CoreDef.TaskMayBlock
            "intrinsic" -> CoreDef.Intrinsic(o.str("name"))
            else -> error("Unknown core definition kind '$kind'")
        }

        private fun witTypeFromJson(o: JsonObject): WitType = when (val k = o.getValue("k").jsonPrimitive.content) {
            "bool" -> WitType.Bool
            "s8" -> WitType.S8
            "u8" -> WitType.U8
            "s16" -> WitType.S16
            "u16" -> WitType.U16
            "s32" -> WitType.S32
            "u32" -> WitType.U32
            "s64" -> WitType.S64
            "u64" -> WitType.U64
            "f32" -> WitType.F32
            "f64" -> WitType.F64
            "char" -> WitType.Char
            "string" -> WitType.Str
            "record" -> WitType.Record(o.getValue("fields").jsonArray.map {
                val f = it.jsonObject
                f.getValue("name").jsonPrimitive.content to witTypeFromJson(f.getValue("type").jsonObject)
            })
            "tuple" -> WitType.Tuple(o.getValue("types").jsonArray.map { witTypeFromJson(it.jsonObject) })
            "list" -> WitType.ListOf(witTypeFromJson(o.getValue("element").jsonObject))
            "flags" -> WitType.Flags(o.getValue("names").jsonArray.map { it.jsonPrimitive.content })
            "enum" -> WitType.Variant(WitType.VariantKind.ENUM, o.getValue("names").jsonArray.map { it.jsonPrimitive.content to null })
            "variant" -> WitType.Variant(WitType.VariantKind.VARIANT, o.getValue("cases").jsonArray.map {
                val c = it.jsonObject
                c.getValue("name").jsonPrimitive.content to c.optType("type")
            })
            "option" -> WitType.Variant(WitType.VariantKind.OPTION, listOf("none" to null, "some" to witTypeFromJson(o.getValue("type").jsonObject)))
            "result" -> WitType.Variant(WitType.VariantKind.RESULT, listOf("ok" to o.optType("ok"), "err" to o.optType("err")))
            "own" -> WitType.Own(o.getValue("table").jsonPrimitive.int)
            "borrow" -> WitType.Borrow(o.getValue("table").jsonPrimitive.int)
            "stream" -> WitType.Stream(o.getValue("table").jsonPrimitive.int)
            "future" -> WitType.Future(o.getValue("table").jsonPrimitive.int)
            "error-context" -> WitType.ErrorContext
            else -> WitType.Unsupported(listOfNotNull(k, o["detail"]?.jsonPrimitive?.content).joinToString(": "))
        }

        private fun funcTypeFromJson(o: JsonObject): FuncType = FuncType(
            params = o.getValue("params").jsonArray.map {
                val p = it.jsonObject
                p.getValue("name").jsonPrimitive.content to witTypeFromJson(p.getValue("type").jsonObject)
            },
            results = o.getValue("results").jsonArray.map { witTypeFromJson(it.jsonObject) },
            async = (o["async"] as? JsonPrimitive)?.content == "true",
        )

        private fun JsonObject.optType(key: String): WitType? =
            this[key]?.takeUnless { it is JsonNull }?.let { witTypeFromJson(it.jsonObject) }
    }
}

internal class PlanModule(val index: Int, val file: String, val imports: List<PlanModuleImport>)

internal class PlanModuleImport(val module: String, val name: String, val kind: String)

internal class PlanImport(val name: String, val path: List<String>) {
    /**
     * Key passed to [wasm2kt.runtime.WitHostImportDispatcher.hostFunction], matching the
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

internal class PlanExport(
    val instance: String?,
    val name: String,
    val func: CoreDef,
    val options: Int,
    val type: FuncType,
) {
    internal val qualifiedName: String = instance?.let { "$it#$name" } ?: name
}

/**
 * Canonical options. [instance] is the component instance they belong to (whose handle table the
 * call uses), [callback] the `callback` of an async lift (an [Initializer.ExtractCallback] index).
 */
internal class CanonOptions(
    val stringEncoding: StringEncoding,
    val memory: Int?,
    val realloc: Int?,
    val postReturn: Int?,
    val async: Boolean,
    val instance: Int = 0,
    val callback: Int? = null,
    val cancellable: Boolean = false,
)

/**
 * [import] names the runtime import of an imported (host-defined) resource; [instance] is the
 * component instance whose handle table holds the handles of this table.
 */
internal class ResourceTableInfo(
    val index: Int,
    val resource: Int?,
    val imported: Boolean,
    val abstract: Boolean,
    val import: Int? = null,
    val instance: Int = 0,
)

/** A stream or future table (plan v5): its component instance and element type (null for `stream`). */
internal class TransmitTableInfo(val index: Int, val instance: Int, val element: WitType?)

internal class CoreExportRef(val instance: Int, val name: String)

internal sealed interface CoreDef {
    data class Export(val ref: CoreExportRef) : CoreDef
    data class Trampoline(val index: Int) : CoreDef
    data class InstanceFlags(val index: Int) : CoreDef

    /** The current task's "may block" flag, an i32 global (plan v3; imported by fused adapters). */
    data object TaskMayBlock : CoreDef

    /** A fused adapter's access to the current thread's context slots, e.g. `context-get-i32-0` (plan v5). */
    data class Intrinsic(val name: String) : CoreDef
}

internal class InstantiateArg(val module: String, val name: String, val def: CoreDef)

internal sealed interface Initializer {
    class Instantiate(val instance: Int, val module: Int, val args: List<InstantiateArg>) : Initializer
    class ExtractMemory(val index: Int, val export: CoreExportRef) : Initializer
    class ExtractRealloc(val index: Int, val def: CoreDef) : Initializer
    class ExtractPostReturn(val index: Int, val def: CoreDef) : Initializer
    class ExtractTable(val index: Int, val export: CoreExportRef) : Initializer
    /** A resource defined by the component instance [instance]. */
    class Resource(val resource: Int, val dtor: CoreDef?, val instance: Int = 0) : Initializer

    /** The `callback` of an async lift (plan v5). */
    class ExtractCallback(val index: Int, val def: CoreDef) : Initializer
}

internal sealed interface Trampoline {
    class LowerImport(val import: Int, val type: FuncType, val options: Int) : Trampoline
    class ResourceNew(val table: Int) : Trampoline
    class ResourceRep(val table: Int) : Trampoline
    class ResourceDrop(val table: Int) : Trampoline

    /** Raises the trap whose code the adapter passes; [messages] are indexed by code (plan v4). */
    class Trap(val messages: List<String?>) : Trampoline

    /** Copies a string between two memories of the component (plan v4; #47). */
    class Transcode(val op: TranscodeOp, val from: Int, val to: Int) : Trampoline
    class Unsupported(val detail: String) : Trampoline

    /**
     * An async built-in (`task.return`, `waitable-set.wait`, `stream.read`, `thread.yield`, ...) or
     * a fused adapter's async intrinsic (`prepare-call`, `stream-transfer`, ...), named by [kind] as
     * in the plan (plan v5, #16). The other fields are set for the kinds that have them.
     */
    class Async(
        val kind: String,
        val instance: Int?,
        val table: Int?,
        val options: Int?,
        val async: Boolean,
        val cancellable: Boolean,
        val results: List<WitType>?,
        val resultsIndex: Int?,
        val memory: Int?,
        val callback: Int?,
        val postReturn: Int?,
    ) : Trampoline
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.optInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: emptyList<JsonElement>()
