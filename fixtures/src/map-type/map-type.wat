;; A component whose export takes a `map<K, V>`, a type the Chicory backend does not support yet (#71).
;; Used to check that builds report it up front (#39) and that only calls using it fail.
(component
  (core module $m
    (memory (export "mem") 1)
    (func (export "realloc") (param i32 i32 i32 i32) (result i32) (i32.const 1024))
    (func (export "size") (param i32 i32) (result i32) (local.get 1))
    (func (export "answer") (result i32) (i32.const 42)))
  (core instance $i (instantiate $m))
  (func (export "size") (param "m" (map string u32)) (result u32)
    (canon lift (core func $i "size") (memory (core memory $i "mem")) (realloc (func $i "realloc"))))
  (func (export "answer") (result u32) (canon lift (core func $i "answer")))
)
