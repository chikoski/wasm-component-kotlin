package wasm2kt

/** [ChicoryDifferentialTest] with Wasmtime driven through the JNI bridge instead of FFM. */
class ChicoryDifferentialJniTest : ChicoryDifferentialTest() {
    override val jni: Boolean = true
}
