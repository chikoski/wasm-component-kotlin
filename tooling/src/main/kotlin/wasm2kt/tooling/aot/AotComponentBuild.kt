package wasm2kt.tooling.aot

import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.Decomposer
import java.nio.file.Path

/**
 * Complete build-time pipeline for a single component using the custom AOT backend
 * (`wasm` -> decompose -> AOT core `.class` + `.data` -> static Kotlin `<Name>Component`),
 * emitting Phase 2 direct static wiring for unmapped synchronous components and Phase 3 async
 * or mapped compatibility wiring where needed.
 *
 * Unlike the Chicory backend in [ComponentBuild], nothing in [ComponentBuild.Output.resourcesDir]
 * contains `plan.json` or `.meta` files, and the generated Kotlin has zero Chicory or
 * `kotlinx.serialization` dependencies.
 */
object AotComponentBuild {
    fun run(
        component: Path,
        componentName: String,
        packageName: String,
        workDir: Path,
        worldName: String? = null,
        interfaceMappings: Map<String, String> = emptyMap(),
        libraryClasspath: List<Path> = emptyList(),
        options: AotCompileOptions = AotCompileOptions(),
    ): ComponentBuild.Output {
        val decomposed = workDir.resolve("decomposed")
        Decomposer.decompose(component, decomposed)
        return runFromDecomposed(
            decomposedDir = decomposed,
            componentName = componentName,
            packageName = packageName,
            workDir = workDir,
            worldName = worldName,
            interfaceMappings = interfaceMappings,
            libraryClasspath = libraryClasspath,
            options = options,
        )
    }

    fun runFromDecomposed(
        decomposedDir: Path,
        componentName: String,
        packageName: String,
        workDir: Path,
        worldName: String? = null,
        interfaceMappings: Map<String, String> = emptyMap(),
        libraryClasspath: List<Path> = emptyList(),
        options: AotCompileOptions = AotCompileOptions(),
    ): ComponentBuild.Output {
        val classes = workDir.resolve("classes")
        val resources = workDir.resolve("resources")
        val kotlin = workDir.resolve("kotlin")
        val internalPackage = ComponentBuild.internalPackage(packageName, componentName)
        val compiled = AotComponentCompiler.compile(
            decomposedDir = decomposedDir,
            classesDir = classes,
            resourcesDir = resources,
            packageName = internalPackage,
            options = options,
        )
        AotComponentCodegen.generate(
            decomposedDir = decomposedDir,
            outputDir = kotlin,
            componentName = componentName,
            packageName = packageName,
            compiled = compiled,
            internalPackage = internalPackage,
            worldName = worldName,
            interfaceMappings = interfaceMappings,
            libraryClasspath = libraryClasspath,
        )
        return ComponentBuild.Output(kotlin, classes, resources)
    }
}
