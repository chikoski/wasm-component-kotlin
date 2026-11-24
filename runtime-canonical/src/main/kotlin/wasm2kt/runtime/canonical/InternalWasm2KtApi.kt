package wasm2kt.runtime.canonical

/**
 * Marks internal Canonical ABI and runtime support APIs exposed for generated bindings and
 * wasm2kt runtime backends. Binary and source compatibility is guaranteed only within the same
 * wasm2kt version.
 */
@RequiresOptIn(
    message = "Internal wasm2kt runtime API for generated code and backends; compatibility is only guaranteed within the same wasm2kt version.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalWasm2KtApi
