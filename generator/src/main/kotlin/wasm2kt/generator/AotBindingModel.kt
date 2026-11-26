package wasm2kt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import wasm2kt.ast.WitFunction
import wasm2kt.ast.WitTypeRef

/**
 * Complete generator-resolved metadata for emitting Phase 2 direct-wired AOT component bindings
 * (`DirectAotComponentEmitter` in `:tooling`) without duplicating WIT naming, keyword escaping,
 * package mapping, or KotlinPoet type resolution logic.
 */
data class AotWorldModel(
    val worldClassName: ClassName,
    val sessionClassName: ClassName,
    val aotImportsClassName: ClassName,
    val hasImports: Boolean,
    val hasMappedImports: Boolean,
    val hasHostResources: Boolean,
    val hasGuestResources: Boolean,
    val exports: Map<String, AotExportBinding>,
    val imports: Map<String, AotImportBinding>,
    val importProviders: List<AotImportProviderInfo>,
    val mappedResourceDropKeys: Set<String>,
    val typeCatalog: AotTypeCatalog,
)

data class AotImportProviderInfo(
    val propName: String,
    val contractClass: ClassName,
)

data class AotValueInfo(
    val kotlinName: String,
    val type: WitTypeRef,
    val kotlinType: TypeName,
)

data class AotExportBinding(
    val key: String,
    val methodName: String,
    val witFunction: WitFunction,
    val params: List<AotValueInfo>,
    val resultTypes: List<WitTypeRef>,
    val returnKotlinType: TypeName,
    val constructedResourceClass: ClassName? = null,
)

data class AotImportBinding(
    val key: String,
    val isMapped: Boolean,
    val providerPropName: String?,
    val kotlinMethodName: String,
    val witFunction: WitFunction,
    val params: List<AotValueInfo>,
    val resultTypes: List<WitTypeRef>,
    val returnKotlinType: TypeName,
)

/** Keyed by `(typeIdx, exportContext)` because reused interfaces have distinct import vs export copies. */
data class AotTypeCatalog(
    val types: Map<AotTypeKey, AotTypeInfo>,
) {
    fun get(typeIdx: Int, exportContext: Boolean): AotTypeInfo =
        types[AotTypeKey(typeIdx, exportContext)]
            ?: throw IllegalStateException("Type index $typeIdx (exportContext=$exportContext) was not cataloged in AotWorldModel")
}

data class AotTypeKey(val index: Int, val exportContext: Boolean)

sealed interface AotTypeInfo {
    data class RecordInfo(
        val className: ClassName,
        val fields: List<AotRecordField>,
    ) : AotTypeInfo

    data class EnumInfo(
        val className: ClassName,
        val entryNames: List<String>,
    ) : AotTypeInfo

    data class FlagsInfo(
        val className: ClassName,
        val useULong: Boolean,
        val flagCount: Int,
    ) : AotTypeInfo

    data class VariantInfo(
        val className: ClassName,
        val cases: List<AotVariantCase>,
    ) : AotTypeInfo

    data class ResourceInfo(
        val className: ClassName,
        val isHost: Boolean,
        val hasSession: Boolean,
    ) : AotTypeInfo

    data class HandleInfo(
        val resourceId: Int,
        val isOwn: Boolean,
        val isHost: Boolean,
        val resourceClass: ClassName,
    ) : AotTypeInfo

    data class OptionInfo(
        val inner: WitTypeRef,
        val isWitOption: Boolean,
        val witOptionClass: ClassName,
        val innerKotlinType: TypeName,
        val kotlinType: TypeName,
    ) : AotTypeInfo

    data class ResultInfo(
        val ok: WitTypeRef?,
        val err: WitTypeRef?,
        val witResultClass: ClassName,
        val okKotlinType: TypeName,
        val errKotlinType: TypeName,
        val kotlinType: TypeName,
    ) : AotTypeInfo

    data class ListInfo(
        val element: WitTypeRef,
        val isByteArray: Boolean,
        val elementKotlinType: TypeName,
    ) : AotTypeInfo

    data class TupleInfo(
        val types: List<WitTypeRef>,
        val tupleClass: ClassName,
        val kotlinType: TypeName,
    ) : AotTypeInfo

    data class TypeAliasInfo(
        val target: WitTypeRef,
    ) : AotTypeInfo

    data class AsyncUnsupportedInfo(
        val detail: String,
    ) : AotTypeInfo
}

data class AotRecordField(
    val kotlinName: String,
    val type: WitTypeRef,
    val kotlinType: TypeName,
)

data class AotVariantCase(
    val className: ClassName,
    val isObject: Boolean,
    val payloadType: WitTypeRef?,
    val payloadKotlinType: TypeName?,
)
