package wasm2kt.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.CoreModuleCompiler
import wasm2kt.tooling.Decomposer
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Splits a component into core modules, `plan.json` and `wit.json`. */
@CacheableTask
abstract class Wasm2KtDecomposeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val component: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun decompose() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        Decomposer.decompose(component.get().asFile.toPath(), out.toPath())
    }
}

/**
 * Compiles the core modules to JVM bytecode and packages them, with their `.meta` modules and the
 * plan, into one jar that is added to the project's dependencies (dexed like any library on Android).
 */
@CacheableTask
abstract class Wasm2KtCompileTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val decomposedDir: DirectoryProperty

    /** JVM package for the compiled classes and resources of this component. */
    @get:Input
    abstract val internalPackage: Property<String>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    @TaskAction
    fun compile() {
        val work = temporaryDir.toPath()
        work.toFile().deleteRecursively()
        val classes = work.resolve("classes")
        val resources = work.resolve("resources")
        val decomposed = decomposedDir.get().asFile.toPath()
        val compiled = CoreModuleCompiler.compile(decomposed, classes, resources, internalPackage.get())
        ComponentBuild.copyPlan(decomposed, resources, internalPackage.get())
        compiled.filter { it.interpretedFunctions > 0 }.forEach {
            logger.warn("wasm2kt: ${it.interpretedFunctions} function(s) of core module ${it.index} are too large for a JVM method and will be interpreted")
        }

        val target = jar.get().asFile
        target.parentFile.mkdirs()
        JarOutputStream(Files.newOutputStream(target.toPath())).use { out ->
            for (root in listOf(classes, resources)) {
                Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                        // Fixed timestamps keep the jar byte-for-byte reproducible for the build cache.
                        val entry = JarEntry(root.relativize(file).toString().replace('\\', '/')).apply { time = REPRODUCIBLE_TIMESTAMP }
                        out.putNextEntry(entry)
                        Files.copy(file, out)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

/** Generates the WIT bindings and the `<Name>Component` entry point. */
@CacheableTask
abstract class Wasm2KtGenerateTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val decomposedDir: DirectoryProperty

    @get:Input
    abstract val componentName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val internalPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val world: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val decomposed = decomposedDir.get().asFile.toPath()
        ComponentCodegen.generate(
            decomposedDir = decomposed,
            outputDir = out.toPath(),
            componentName = componentName.get(),
            packageName = packageName.get(),
            compiled = CoreModuleCompiler.layout(decomposed, internalPackage.get()),
            planResource = "/" + internalPackage.get().replace('.', '/') + "/plan.json",
            worldName = world.orNull,
        )
    }
}

/** 1980-02-01, the same constant Gradle uses for reproducible archives (zip has no earlier dates). */
private const val REPRODUCIBLE_TIMESTAMP = 318211200000L
