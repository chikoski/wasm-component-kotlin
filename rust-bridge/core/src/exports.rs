//! The C ABI (FFM) and JNI entry points. A bridge library exports them once, with its typed
//! components, by invoking [`export_bridge!`] at its crate root (ADR-0016): the generic bridge with
//! none, a generated typed bridge with its own. Both libraries export the same symbols, so the Kotlin
//! side (`runtime-wasmtime`) loads either one the same way.

/// Exports the bridge's C ABI and JNI functions, opening typed sessions with the components in
/// `$registry` (a [`crate::Registry`]).
#[macro_export]
macro_rules! export_bridge {
    ($registry:expr) => {
        const _: () = {
            use std::os::raw::c_char;
            use $crate::jni::objects::{JByteArray, JClass, JObject, JString};
            use $crate::jni::sys::{jboolean, jbyteArray, jint, jlong};
            use $crate::jni::JNIEnv;
            use $crate::{BridgeSession, HostCallbackFn};

            const REGISTRY: $crate::Registry = $registry;

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_open(
                component_id_ptr: *const c_char,
                wasm_path_ptr: *const c_char,
                host_callback: Option<HostCallbackFn>,
                precompiled: bool,
                err_buf: *mut c_char,
                err_buf_cap: usize,
            ) -> *mut BridgeSession {
                $crate::ffi_session_open(REGISTRY, component_id_ptr, wasm_path_ptr, host_callback, precompiled, err_buf, err_buf_cap)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_open_bytes(
                component_id_ptr: *const c_char,
                wasm_bytes_ptr: *const u8,
                wasm_bytes_len: usize,
                host_callback: Option<HostCallbackFn>,
                precompiled: bool,
                err_buf: *mut c_char,
                err_buf_cap: usize,
            ) -> *mut BridgeSession {
                $crate::ffi_session_open_bytes(
                    REGISTRY,
                    component_id_ptr,
                    wasm_bytes_ptr,
                    wasm_bytes_len,
                    host_callback,
                    precompiled,
                    err_buf,
                    err_buf_cap,
                )
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_precompile_component(
                wasm_bytes_ptr: *const u8,
                wasm_bytes_len: usize,
                out_len_ptr: *mut usize,
                err_buf: *mut c_char,
                err_buf_cap: usize,
            ) -> *mut u8 {
                $crate::ffi_precompile_component(wasm_bytes_ptr, wasm_bytes_len, out_len_ptr, err_buf, err_buf_cap)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_alloc_bytes(len: usize) -> *mut u8 {
                $crate::ffi_alloc_bytes(len)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_free_bytes(ptr: *mut u8, len: usize) {
                $crate::ffi_free_bytes(ptr, len)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_resolve(
                session_ptr: *mut BridgeSession,
                name_ptr: *const c_char,
                out_index: *mut u32,
                out_ptr: *mut *mut u8,
                out_len: *mut usize,
            ) -> i32 {
                $crate::ffi_session_resolve(session_ptr, name_ptr, out_index, out_ptr, out_len)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_invoke(
                session_ptr: *mut BridgeSession,
                index: u32,
                args_ptr: *const u8,
                args_len: usize,
                out_ptr: *mut *mut u8,
                out_len: *mut usize,
            ) -> i32 {
                $crate::ffi_session_invoke(session_ptr, index, args_ptr, args_len, out_ptr, out_len)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_drop_resource(
                session_ptr: *mut BridgeSession,
                handle: i32,
                out_ptr: *mut *mut u8,
                out_len: *mut usize,
            ) -> i32 {
                $crate::ffi_session_drop_resource(session_ptr, handle, out_ptr, out_len)
            }

            #[no_mangle]
            pub extern "C" fn wasm2kt_session_close(session_ptr: *mut BridgeSession) {
                $crate::ffi_session_close(session_ptr)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionOpen<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                component_id: JString<'local>,
                wasm_path: JString<'local>,
                host_callback: JObject<'local>,
                precompiled: jboolean,
            ) -> jlong {
                $crate::jni_session_open(REGISTRY, env, class, component_id, wasm_path, host_callback, precompiled)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionOpenBytes<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                component_id: JString<'local>,
                wasm_bytes: JByteArray<'local>,
                host_callback: JObject<'local>,
                precompiled: jboolean,
            ) -> jlong {
                $crate::jni_session_open_bytes(REGISTRY, env, class, component_id, wasm_bytes, host_callback, precompiled)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionResolve<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                session_ptr: jlong,
                name: JString<'local>,
            ) -> jint {
                $crate::jni_session_resolve(env, class, session_ptr, name)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionInvoke<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                session_ptr: jlong,
                index: jint,
                args: JByteArray<'local>,
                args_len: jint,
            ) -> jbyteArray {
                $crate::jni_session_invoke(env, class, session_ptr, index, args, args_len)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionDropResource<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                session_ptr: jlong,
                handle: jint,
            ) {
                $crate::jni_session_drop_resource(env, class, session_ptr, handle)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativeSessionClose<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                session_ptr: jlong,
            ) {
                $crate::jni_session_close(env, class, session_ptr)
            }

            #[no_mangle]
            pub extern "system" fn Java_wasm2kt_runtime_JniWasmtimeBridge_nativePrecompileComponent<'local>(
                env: JNIEnv<'local>,
                class: JClass<'local>,
                wasm_bytes: JByteArray<'local>,
            ) -> jbyteArray {
                $crate::jni_precompile_component(env, class, wasm_bytes)
            }
        };
    };
}
