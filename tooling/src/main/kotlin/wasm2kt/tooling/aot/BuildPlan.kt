@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Build-time representation of `plan.json` emitted by `wasm2kt-decompose`. Used exclusively in
 * `:tooling` to compile core modules and generate static Kotlin component wiring without shipping
 * `plan.json` or `kotlinx.serialization` at runtime.
 */
class BuildPlan internal constructor(
    internal val modules: List<BuildPlanModule>,
    internal val initializers: List<BuildInitializer>,
    internal val trampolines: Map<Int, BuildTrampoline>,
    internal val options: Map<Int, BuildCanonOptions>,
    internal val imports: Map<Int, BuildPlanImport>,
    internal val resourceTables: Map<Int, BuildResourceTableInfo>,
    internal val exports: List<BuildPlanExport>,
    internal val streamTables: Map<Int, BuildTransmitTableInfo> = emptyMap(),
    internal val futureTables: Map<Int, BuildTransmitTableInfo> = emptyMap(),
    internal val errorContextTables: Map<Int, Int> = emptyMap(),
) {
    /** Single source of truth shared with `:runtime-chicory` via [PlanAsyncRules]. */
    val usesAsync: Boolean by lazy {
        PlanAsyncRules.usesAsync(
            trampolineKinds = trampolines.values.asSequence().filterIsInstance<BuildTrampoline.Async>().map { it.kind }.asIterable(),
            hasAsyncOptionOrCallback = options.values.any { it.async || it.callback != null },
            hasAsyncExport = exports.any { it.type.async },
            hasExtractCallback = initializers.any { it is BuildInitializer.ExtractCallback },
        )
    }

    companion object {
        const val SUPPORTED_VERSION = 5

        fun fromDirectory(decomposedDir: Path): BuildPlan =
            parse(Files.readString(decomposedDir.resolve("plan.json")))

        fun parse(json: String): BuildPlan = fromJson(Json.parseToJsonElement(json).jsonObject)

        internal fun fromJson(root: JsonObject): BuildPlan {
            val version = root.int("version")
            require(version == SUPPORTED_VERSION) {
                "Unsupported plan version $version (expected $SUPPORTED_VERSION); regenerate it with a matching wasm2kt-decompose"
            }
            return BuildPlan(
                modules = root.array("modules").map { m ->
                    val o = m.jsonObject
                    BuildPlanModule(
                        index = o.int("index"),
                        file = o.str("file"),
                        imports = o.array("imports").map { i ->
                            val io = i.jsonObject
                            BuildPlanModuleImport(io.str("module"), io.str("name"), io.str("kind"))
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
                    o.int("index") to BuildCanonOptions(
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
                    o.int("index") to BuildPlanImport(o.str("name"), o.array("path").map { it.jsonPrimitive.content })
                },
                resourceTables = root.array("resourceTables").associate { rt ->
                    val o = rt.jsonObject
                    o.int("index") to BuildResourceTableInfo(
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
                    BuildPlanExport(
                        instance = o["instance"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                        name = o.str("name"),
                        func = parseDef(o.getValue("func").jsonObject),
                        options = o.int("options"),
                        type = funcTypeFromJson(o.getValue("type").jsonObject),
                    )
                },
            )
        }

        private fun parseTransmitTables(root: JsonObject, key: String): Map<Int, BuildTransmitTableInfo> =
            root.array(key).associate { t ->
                val o = t.jsonObject
                o.int("index") to BuildTransmitTableInfo(
                    index = o.int("index"),
                    instance = o.int("instance"),
                    element = o["element"]?.takeUnless { it is JsonNull }?.let { witTypeFromJson(it.jsonObject) },
                )
            }

        private fun parseInitializer(o: JsonObject): BuildInitializer = when (val op = o.str("op")) {
            "instantiate" -> BuildInitializer.Instantiate(
                instance = o.int("instance"),
                module = o.int("module"),
                args = o.array("args").map { a ->
                    val ao = a.jsonObject
                    BuildInstantiateArg(ao.str("module"), ao.str("name"), parseDef(ao.getValue("def").jsonObject))
                },
            )
            "extract-memory" -> BuildInitializer.ExtractMemory(o.int("index"), parseExport(o.getValue("export").jsonObject))
            "extract-realloc" -> BuildInitializer.ExtractRealloc(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "extract-post-return" -> BuildInitializer.ExtractPostReturn(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "extract-table" -> BuildInitializer.ExtractTable(o.int("index"), parseExport(o.getValue("export").jsonObject))
            "extract-callback" -> BuildInitializer.ExtractCallback(o.int("index"), parseDef(o.getValue("def").jsonObject))
            "resource" -> BuildInitializer.Resource(
                resource = o.int("resource"),
                dtor = o["dtor"]?.takeUnless { it is JsonNull }?.let { parseDef(it.jsonObject) },
                instance = o.optInt("instance") ?: 0,
            )
            else -> error("Unknown plan initializer '$op'")
        }

        private fun parseTrampoline(o: JsonObject): BuildTrampoline = when (val kind = o.str("kind")) {
            "lower-import" -> BuildTrampoline.LowerImport(
                import = o.int("import"),
                type = funcTypeFromJson(o.getValue("type").jsonObject),
                options = o.int("options"),
            )
            "resource-new" -> BuildTrampoline.ResourceNew(o.int("table"))
            "resource-rep" -> BuildTrampoline.ResourceRep(o.int("table"))
            "resource-drop" -> BuildTrampoline.ResourceDrop(o.int("table"))
            "trap" -> BuildTrampoline.Trap(o.array("messages").map { (it as? JsonPrimitive)?.takeUnless { m -> m is JsonNull }?.content })
            "transcode" -> {
                val op = o.str("op")
                TranscodeOp.fromPlan(op)?.let { BuildTrampoline.Transcode(it, o.int("from"), o.int("to")) }
                    ?: BuildTrampoline.Unsupported("transcode $op")
            }
            "unsupported" -> BuildTrampoline.Unsupported(o["detail"]?.jsonPrimitive?.content ?: kind)
            else -> BuildTrampoline.Async(
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

        private fun parseExport(o: JsonObject) = BuildCoreExportRef(o.int("instance"), o.str("name"))

        private fun parseDef(o: JsonObject): BuildCoreDef = when (val kind = o.str("kind")) {
            "export" -> BuildCoreDef.Export(parseExport(o))
            "trampoline" -> BuildCoreDef.Trampoline(o.int("index"))
            "instance-flags" -> BuildCoreDef.InstanceFlags(o.int("index"))
            "task-may-block" -> BuildCoreDef.TaskMayBlock
            "intrinsic" -> BuildCoreDef.Intrinsic(o.str("name"))
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

internal class BuildPlanModule(val index: Int, val file: String, val imports: List<BuildPlanModuleImport>)

internal class BuildPlanModuleImport(val module: String, val name: String, val kind: String)

internal class BuildPlanImport(val name: String, val path: List<String>) {
    val dispatchKey: String
        get() = if (path.isEmpty()) name else "$name#${path.joinToString("#")}"

    val resourceDropKey: String
        get() = if (path.isEmpty()) "[resource-drop]$name" else "$name#[resource-drop]${path.last()}"
}

internal class BuildPlanExport(
    val instance: String?,
    val name: String,
    val func: BuildCoreDef,
    val options: Int,
    val type: FuncType,
)

internal class BuildCanonOptions(
    val stringEncoding: StringEncoding,
    val memory: Int?,
    val realloc: Int?,
    val postReturn: Int?,
    val async: Boolean,
    val instance: Int = 0,
    val callback: Int? = null,
    val cancellable: Boolean = false,
)

internal class BuildResourceTableInfo(
    val index: Int,
    val resource: Int?,
    val imported: Boolean,
    val abstract: Boolean,
    val import: Int? = null,
    val instance: Int = 0,
)

internal class BuildTransmitTableInfo(val index: Int, val instance: Int, val element: WitType?)

internal class BuildCoreExportRef(val instance: Int, val name: String)

internal sealed interface BuildCoreDef {
    data class Export(val ref: BuildCoreExportRef) : BuildCoreDef
    data class Trampoline(val index: Int) : BuildCoreDef
    data class InstanceFlags(val index: Int) : BuildCoreDef
    data object TaskMayBlock : BuildCoreDef
    data class Intrinsic(val name: String) : BuildCoreDef
}

internal class BuildInstantiateArg(val module: String, val name: String, val def: BuildCoreDef)

internal sealed interface BuildInitializer {
    class Instantiate(val instance: Int, val module: Int, val args: List<BuildInstantiateArg>) : BuildInitializer
    class ExtractMemory(val index: Int, val export: BuildCoreExportRef) : BuildInitializer
    class ExtractRealloc(val index: Int, val def: BuildCoreDef) : BuildInitializer
    class ExtractPostReturn(val index: Int, val def: BuildCoreDef) : BuildInitializer
    class ExtractTable(val index: Int, val export: BuildCoreExportRef) : BuildInitializer
    class Resource(val resource: Int, val dtor: BuildCoreDef?, val instance: Int = 0) : BuildInitializer
    class ExtractCallback(val index: Int, val def: BuildCoreDef) : BuildInitializer
}

internal sealed interface BuildTrampoline {
    class LowerImport(val import: Int, val type: FuncType, val options: Int) : BuildTrampoline
    class ResourceNew(val table: Int) : BuildTrampoline
    class ResourceRep(val table: Int) : BuildTrampoline
    class ResourceDrop(val table: Int) : BuildTrampoline
    class Trap(val messages: List<String?>) : BuildTrampoline
    class Transcode(val op: TranscodeOp, val from: Int, val to: Int) : BuildTrampoline
    class Unsupported(val detail: String) : BuildTrampoline
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
    ) : BuildTrampoline
}

private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.optInt(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int
private fun JsonObject.array(key: String) = this[key]?.jsonArray ?: emptyList<JsonElement>()
