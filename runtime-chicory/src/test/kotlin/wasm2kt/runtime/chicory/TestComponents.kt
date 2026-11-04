package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import wasm2kt.testing.JsonBackend
import wasm2kt.testing.JsonHostImports
import wasm2kt.testing.PlanTypes
import java.nio.file.Path
import java.nio.file.Paths

/** A decomposed test component under `src/test/resources/components`, driven with JSON-written values. */
internal class TestComponent(name: String) {
    val dir: Path = Paths.get(
        requireNotNull(TestComponent::class.java.getResource("/components/$name/plan.json")) { "missing test component $name" }.toURI(),
    ).parent
    val types = PlanTypes.fromDirectory(dir)

    /** A new instance; [imports] answers host imports with JSON-written values. */
    fun open(imports: ((key: String, args: JsonArray) -> JsonElement)? = null): JsonBackend =
        JsonBackend(ChicoryComponent.fromDirectory(dir).instantiate(imports?.let { JsonHostImports(types, it) }), types)
}
