;; Two nested components passing a resource between them: wasmtime-environ fuses an adapter that
;; uses component intrinsics (resource transfer, sync call enter/exit; #70).
(component
  (component $a
    (type $r (resource (rep i32)))
    (core func $new (canon resource.new $r))
    (core module $m
      (import "" "new" (func $new (param i32) (result i32)))
      (func (export "make") (result i32) (call $new (i32.const 7))))
    (core instance $i (instantiate $m (with "" (instance (export "new" (func $new))))))
    (export $rx "r" (type $r))
    (func $make (result (own $rx)) (canon lift (core func $i "make")))
    (export "make" (func $make)))
  (instance $ai (instantiate $a))
  (alias export $ai "r" (type $r))
  (component $b
    (import "r" (type $r (sub resource)))
    (import "make" (func $make (result (own $r))))
    (core func $lmake (canon lower (func $make)))
    (core func $drop (canon resource.drop $r))
    (core module $user
      (import "" "make" (func $make (result i32)))
      (import "" "drop" (func $drop (param i32)))
      (func (export "g") (result i32) (local $h i32) (local.set $h (call $make)) (call $drop (local.get $h)) (local.get $h)))
    (core instance $ui (instantiate $user (with "" (instance (export "make" (func $lmake)) (export "drop" (func $drop))))))
    (func (export "g") (result u32) (canon lift (core func $ui "g"))))
  (instance $bi (instantiate $b (with "r" (type $r)) (with "make" (func $ai "make"))))
  (export "g" (func $bi "g"))
)
