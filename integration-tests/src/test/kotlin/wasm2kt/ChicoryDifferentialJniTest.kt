package wasm2kt

/** [ChicoryDifferentialTest] with Wasmtime driven through the JNI bridge (the Android-style path). */
class ChicoryDifferentialJniTest : ChicoryDifferentialTest() {
    override val jni: Boolean = true
}
