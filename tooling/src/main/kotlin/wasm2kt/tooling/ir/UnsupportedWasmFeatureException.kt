package wasm2kt.tooling.ir

/**
 * Thrown at build time when a Core Wasm module uses a Wasm proposal, section,
 * type, or instruction outside the supported compiler subset.
 */
class UnsupportedWasmFeatureException(
    val feature: String,
    val moduleIndex: Int,
    val functionIndex: Int? = null,
    val instructionName: String? = null,
    val detail: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildMessage(feature, moduleIndex, functionIndex, instructionName, detail),
    cause,
) {
    private companion object {
        fun buildMessage(
            feature: String,
            moduleIndex: Int,
            functionIndex: Int?,
            instructionName: String?,
            detail: String?,
        ): String = buildString {
            append("Unsupported Wasm feature '")
            append(feature)
            append("' in core module ")
            append(moduleIndex)
            if (functionIndex != null) {
                append(", function ")
                append(functionIndex)
            }
            if (instructionName != null) {
                append(", instruction '")
                append(instructionName)
                append("'")
            }
            if (!detail.isNullOrBlank()) {
                append(": ")
                append(detail)
            }
        }
    }
}
