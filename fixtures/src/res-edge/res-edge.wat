;; A hand-written component that defines a resource and exposes raw handle numbers, so tests can
;; make the guest reuse a handle it already gave away. Used to check that lifting `own` removes
;; the handle from the component's table and lowering `own` creates a fresh one (#31).
(component
  ;; The destructor records the last rep it destroyed.
  (core module $d
    (global (export "last") (mut i32) (i32.const 0))
    (func (export "dtor") (param i32) (global.set 0 (local.get 0))))
  (core instance $di (instantiate $d))

  (type $r (resource (rep i32) (dtor (func $di "dtor"))))
  (core func $new (canon resource.new $r))
  (core func $drop (canon resource.drop $r))
  (core func $rep (canon resource.rep $r))

  (core module $m
    (import "d" "last" (global $last (mut i32)))
    (import "r" "new" (func $new (param i32) (result i32)))
    (import "r" "drop" (func $drop (param i32)))
    (import "r" "rep" (func $rep (param i32) (result i32)))
    ;; Creates a resource with the given rep and returns its own handle.
    (func (export "make") (param i32) (result i32) (call $new (local.get 0)))
    ;; Returns a raw handle number as `own`: valid only if the component still holds it.
    (func (export "same") (param i32) (result i32) (local.get 0))
    ;; Calls resource.drop / resource.rep on a raw handle number.
    (func (export "drop-raw") (param i32) (call $drop (local.get 0)))
    (func (export "rep-raw") (param i32) (result i32) (call $rep (local.get 0)))
    ;; Receives `own` and returns the handle number the component got for it (keeping the handle).
    (func (export "take") (param i32) (result i32) (local.get 0))
    (func (export "take2") (param i32 i32) (result i32) (local.get 0))
    ;; Receives `borrow`: the defining instance gets the rep itself.
    (func (export "peek") (param i32) (result i32) (local.get 0))
    (func (export "last-dropped") (result i32) (global.get $last))
  )
  (core instance $mi (instantiate $m
    (with "d" (instance $di))
    (with "r" (instance
      (export "new" (func $new))
      (export "drop" (func $drop))
      (export "rep" (func $rep))))))

  (func $make (param "rep" u32) (result (own $r)) (canon lift (core func $mi "make")))
  (func $same (param "handle" u32) (result (own $r)) (canon lift (core func $mi "same")))
  (func $drop-raw (param "handle" u32) (canon lift (core func $mi "drop-raw")))
  (func $rep-raw (param "handle" u32) (result u32) (canon lift (core func $mi "rep-raw")))
  (func $take (param "r" (own $r)) (result u32) (canon lift (core func $mi "take")))
  (func $take2 (param "a" (own $r)) (param "b" (own $r)) (result u32) (canon lift (core func $mi "take2")))
  (func $peek (param "r" (borrow $r)) (result u32) (canon lift (core func $mi "peek")))
  (func $last-dropped (result u32) (canon lift (core func $mi "last-dropped")))
  (instance $api
    (export "res" (type $r))
    (export "make" (func $make))
    (export "same" (func $same))
    (export "drop-raw" (func $drop-raw))
    (export "rep-raw" (func $rep-raw))
    (export "take" (func $take))
    (export "take2" (func $take2))
    (export "peek" (func $peek))
    (export "last-dropped" (func $last-dropped)))
  (export "example:res-edge/api" (instance $api))
)
