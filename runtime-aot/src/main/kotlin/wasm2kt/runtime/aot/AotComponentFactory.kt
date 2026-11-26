package wasm2kt.runtime.aot

/**
 * Instantiates a Phase 2 AOT-compiled component directly into its generated World DSL session
 * without going through [wasm2kt.runtime.ComponentBackend] or the binary wire format (ADR-0023).
 */
interface AotComponentFactory<in I, out S : AutoCloseable> {
    fun instantiate(imports: I): S
}
