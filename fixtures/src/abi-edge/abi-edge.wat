;; A hand-written component whose core code returns values that the Canonical ABI must reject.
;; Every export takes the raw core value from its arguments, so tests choose what the guest
;; "returns". Used by the Chicory/Wasmtime differential tests for trap behaviour (#18).
(component
  (import "ping" (func $ping))

  (core module $m
    (import "host" "ping" (func $ping))
    (memory (export "memory") 1)
    ;; realloc behaviour: 0 = bump allocator, 1 = misaligned result, 2 = out of bounds,
    ;; 3 = calls an import (must trap: may_leave is false during realloc)
    (global $mode (mut i32) (i32.const 0))
    (global $bump (mut i32) (i32.const 0x1000))
    (data (i32.const 0x100) "\ff\fe\fd")        ;; invalid UTF-8
    (data (i32.const 0x200) "hello")
    (data (i32.const 0x300) "\01\00\00\00\02\00\00\00\03\00\00\00")
    (data (i32.const 0x310) "a\00\00\d8")        ;; UTF-16LE "a" + lone high surrogate

    (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32)
      (local $p i32)
      (if (i32.eq (global.get $mode) (i32.const 1))
        (then (return (i32.add (global.get $bump) (i32.const 1)))))
      (if (i32.eq (global.get $mode) (i32.const 2))
        (then (return (i32.const 0xFFFF0000))))
      (if (i32.eq (global.get $mode) (i32.const 3))
        (then (call $ping)))
      ;; align up
      (local.set $p
        (i32.and
          (i32.add (global.get $bump) (i32.sub (local.get 2) (i32.const 1)))
          (i32.sub (i32.const 0) (local.get 2))))
      (global.set $bump (i32.add (local.get $p) (local.get 3)))
      (local.get $p))

    (func (export "set-mode") (param i32) (global.set $mode (local.get 0)))
    (func (export "char-from") (param i32) (result i32) (local.get 0))
    (func (export "result-from") (param i32) (result i32) (local.get 0))
    (func (export "option-from") (param i32) (result i32)
      (i32.store8 (i32.const 0x20) (local.get 0))
      (i32.store (i32.const 0x24) (i32.const 7))
      (i32.const 0x20))
    (func $ptr-len (param i32 i32) (result i32)
      (i32.store (i32.const 0x10) (local.get 0))
      (i32.store (i32.const 0x14) (local.get 1))
      (i32.const 0x10))
    (export "string-at" (func $ptr-len))
    (export "list-at" (func $ptr-len))
    (export "utf16-at" (func $ptr-len))
    (func (export "string-ret") (param i32) (result i32) (local.get 0))
    (func (export "list-len") (param i32 i32) (result i32) (local.get 1))
    (func (export "call-ping") (call $ping))
  )

  (core func $ping-lowered (canon lower (func $ping)))
  (core instance $host (export "ping" (func $ping-lowered)))
  (core instance $i (instantiate $m (with "host" (instance $host))))
  (alias core export $i "memory" (core memory $mem))
  (alias core export $i "cabi_realloc" (core func $realloc))

  (func (export "set-mode") (param "mode" u32) (canon lift (core func $i "set-mode")))
  (func (export "char-from") (param "n" u32) (result char) (canon lift (core func $i "char-from")))
  (func (export "result-from") (param "n" u32) (result (result)) (canon lift (core func $i "result-from")))
  (func (export "option-from") (param "disc" u32) (result (option u32))
    (canon lift (core func $i "option-from") (memory $mem)))
  (func (export "string-at") (param "ptr" u32) (param "len" u32) (result string)
    (canon lift (core func $i "string-at") (memory $mem) string-encoding=utf8))
  (func (export "utf16-at") (param "ptr" u32) (param "len" u32) (result string)
    (canon lift (core func $i "utf16-at") (memory $mem) string-encoding=utf16))
  (func (export "list-at") (param "ptr" u32) (param "len" u32) (result (list u32))
    (canon lift (core func $i "list-at") (memory $mem)))
  (func (export "string-ret") (param "retptr" u32) (result string)
    (canon lift (core func $i "string-ret") (memory $mem)))
  (func (export "list-len") (param "items" (list u32)) (result u32)
    (canon lift (core func $i "list-len") (memory $mem) (realloc $realloc)))
  (func (export "call-ping") (canon lift (core func $i "call-ping")))
)
