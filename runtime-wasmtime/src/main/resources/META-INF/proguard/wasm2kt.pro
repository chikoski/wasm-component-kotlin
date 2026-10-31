# Consumer R8 / ProGuard rules for wasm2kt runtime.
#
# These classes and members are looked up by name from native code (JNI `GetMethodID` /
# `call_method`) or via `MethodHandles.lookup().bind(...)` (FFM upcall), so they must not be
# removed, renamed, or have their signatures changed by shrinking / obfuscation.

-keep class wasm2kt.runtime.JniHostCallback {
    public java.lang.String onHostCall(java.lang.String, java.lang.String);
}
-keep class wasm2kt.runtime.HostCallbackDispatcher {
    public int onHostCall(java.lang.foreign.MemorySegment, java.lang.foreign.MemorySegment, java.lang.foreign.MemorySegment, java.lang.foreign.MemorySegment);
}
-keep class wasm2kt.runtime.JniWasmtimeBridge {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class wasm2kt.runtime.** {
    native <methods>;
}
-dontwarn java.lang.foreign.**
