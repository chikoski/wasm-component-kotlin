package wasm2kt.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.CoreModuleCompiler
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.PlanCheck
import wasm2kt.tooling.aot.AotComponentCodegen
import wasm2kt.tooling.aot.AotComponentCompiler
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.inject.Inject

/**
 * Base for tasks that run wasm2kt's tooling. The tooling (Chicory's compiler, ASM, KotlinPoet, ...)
 * runs in an isolated class loader so it cannot clash with the Android Gradle plugin's own ASM.
 */
@DisableCachingByDefault(because = "Abstract base task; concrete subclasses opt into caching.")
abstract class Wasm2KtToolingTask : DefaultTask() {
    /** `io.github.wasm2kt:tooling` and its dependencies, from the `wasm2ktTooling` configuration. */
    @get:Classpath
    abstract val toolingClasspath: ConfigurableFileCollection

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    protected fun <P : WorkParameters> submit(action: Class<out WorkAction<P>>, configure: (P) -> Unit) {
        workerExecutor.classLoaderIsolation { it.classpath.from(toolingClasspath) }.submit(action) { configure(it) }
    }
}

/** Splits a component into core modules, `plan.json` and `wit.json`. */
@CacheableTask
abstract class Wasm2KtDecomposeTask : Wasm2KtToolingTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val component: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun decompose() = submit(DecomposeAction::class.java) {
        it.component.set(component)
        it.outputDir.set(outputDir)
    }

    interface Params : WorkParameters {
        val component: RegularFileProperty
        val outputDir: DirectoryProperty
    }

    abstract class DecomposeAction : WorkAction<Params> {
        override fun execute() {
            val out = parameters.outputDir.get().asFile
            out.deleteRecursively()
            val component = parameters.component.get().asFile
            Decomposer.decompose(component.toPath(), out.toPath())
        }
    }
}

/** Validates a decomposed component against the configured backend (#39, #78, #186). */
@CacheableTask
abstract class Wasm2KtCheckTask : Wasm2KtToolingTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val decomposedDir: DirectoryProperty

    @get:Input
    abstract val sourceName: Property<String>

    @get:Input
    abstract val backend: Property<String>

    /** Fail the build, instead of warning, when the component uses features the backend cannot run. */
    @get:Input
    abstract val failOnUnsupportedFeatures: Property<Boolean>

    @get:OutputFile
    abstract val warnings: RegularFileProperty

    @TaskAction
    fun check() = submit(CheckAction::class.java) {
        it.decomposedDir.set(decomposedDir)
        it.sourceName.set(sourceName)
        it.backend.set(backend)
        it.failOnUnsupportedFeatures.set(failOnUnsupportedFeatures)
        it.warnings.set(warnings)
    }

    interface Params : WorkParameters {
        val decomposedDir: DirectoryProperty
        val sourceName: Property<String>
        val backend: Property<String>
        val failOnUnsupportedFeatures: Property<Boolean>
        val warnings: RegularFileProperty
    }

    abstract class CheckAction : WorkAction<Params> {
        override fun execute() {
            val decomposed = parameters.decomposedDir.get().asFile.toPath()
            val sourceName = parameters.sourceName.get()
            val backend = parameters.backend.get()
            val warningsFile = parameters.warnings.get().asFile
            warningsFile.parentFile.mkdirs()

            if (backend == "AOT" && PlanCheck.usesAsync(decomposed)) {
                warningsFile.delete()
                throw IllegalStateException(
                    "wasm2kt: $sourceName uses async features (usesAsync = true), which the AOT backend does not support yet; " +
                        "switch this component to Wasm2KtBackend.CHICORY",
                )
            }

            // Report what would otherwise only fail on the device, the first time it is used (#39).
            val problems = PlanCheck.unsupportedFeatures(decomposed)
            if (problems.isEmpty()) {
                warningsFile.writeText("")
                return
            }
            val report = "wasm2kt: $sourceName uses features this backend does not support yet; " +
                "calls that reach them will throw at run time:\n" + problems.joinToString("\n") { "  - $it" }
            if (parameters.failOnUnsupportedFeatures.get()) {
                warningsFile.delete()
                throw IllegalStateException("$report\n(set wasm2kt.failOnUnsupportedFeatures = false to build anyway)")
            }
            // Printed by Wasm2KtWarningsTask, also when this task is UP-TO-DATE or FROM-CACHE (#78).
            warningsFile.writeText("$report\n(set wasm2kt.failOnUnsupportedFeatures = true to fail the build instead)\n")
        }
    }
}

/**
 * Compiles the core modules to JVM bytecode and packages them into one jar that is added to the
 * project's dependencies (dexed like any library on Android). For `CHICORY`, includes `.meta`
 * modules and `plan.json`; for `AOT`, emits only `.class` files and `.data` segment resources.
 */
@CacheableTask
abstract class Wasm2KtCompileTask : Wasm2KtToolingTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val decomposedDir: DirectoryProperty

    /** JVM package for the compiled classes and resources of this component. */
    @get:Input
    abstract val internalPackage: Property<String>

    @get:Input
    abstract val backend: Property<String>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    /** The build-time warnings of this compilation, printed by [Wasm2KtWarningsTask] on every build (#78). */
    @get:OutputFile
    abstract val warnings: RegularFileProperty

    @TaskAction
    fun compile() = submit(CompileAction::class.java) {
        it.decomposedDir.set(decomposedDir)
        it.internalPackage.set(internalPackage)
        it.backend.set(backend)
        it.jar.set(jar)
        it.warnings.set(warnings)
        it.workDir.set(temporaryDir)
    }

    interface Params : WorkParameters {
        val decomposedDir: DirectoryProperty
        val internalPackage: Property<String>
        val backend: Property<String>
        val jar: RegularFileProperty
        val warnings: RegularFileProperty
        val workDir: DirectoryProperty
    }

    abstract class CompileAction : WorkAction<Params> {
        private val logger = org.gradle.api.logging.Logging.getLogger(Wasm2KtCompileTask::class.java)

        override fun execute() {
            val work = parameters.workDir.get().asFile.toPath()
            work.toFile().deleteRecursively()
            val classes = work.resolve("classes")
            val resources = work.resolve("resources")
            val decomposed = parameters.decomposedDir.get().asFile.toPath()
            val pkg = parameters.internalPackage.get()
            val warnings = if (parameters.backend.get() == "AOT") {
                val compiled = AotComponentCompiler.compile(decomposed, classes, resources, pkg)
                for ((index, mod) in compiled.modules) {
                    val s = mod.stats
                    if (s.splitFunctions > 0) {
                        logger.info(
                            "wasm2kt (AOT): split {} large method(s) in core module {} (max {} bytes after splitting)",
                            s.splitFunctions,
                            index,
                            s.maxMethodCodeBytes,
                        )
                    }
                }
                AotComponentCompiler.warningsFor(compiled).joinToString(separator = "") { "$it\n" }
            } else {
                val compiled = CoreModuleCompiler.compile(decomposed, classes, resources, pkg)
                ComponentBuild.copyPlan(decomposed, resources, pkg)
                for (module in compiled) {
                    if (module.splitMethods > 0) {
                        logger.info(
                            "wasm2kt: split {} large method(s) in core module {} (max {} bytes after splitting)",
                            module.splitMethods,
                            module.index,
                            module.maxMethodCodeBytes,
                        )
                    }
                }
                CoreModuleCompiler.warningsFor(compiled).joinToString(separator = "") { "$it\n" }
            }
            parameters.warnings.get().asFile.apply { parentFile.mkdirs() }.writeText(warnings)
            writeJar(parameters.jar.get().asFile, listOf(classes, resources).map { it.toFile() })
        }

        private fun writeJar(target: File, roots: List<File>) {
            target.parentFile.mkdirs()
            JarOutputStream(Files.newOutputStream(target.toPath())).use { out ->
                for (root in roots.map { it.toPath() }) {
                    if (!Files.exists(root)) continue
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
}

/** Generates the WIT bindings and the `<Name>Component` entry point. */
@CacheableTask
abstract class Wasm2KtGenerateTask : Wasm2KtToolingTask() {
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
    abstract val backend: Property<String>

    @get:Input
    @get:Optional
    abstract val world: Property<String>

    /** Explicit `with` mappings: imported interface to the Kotlin package of existing bindings (ADR-0014). */
    @get:Input
    abstract val interfaceMappings: MapProperty<String, String>

    /** Jars and class directories whose wasm2kt interface manifests provide interfaces (ADR-0014). */
    @get:Classpath
    abstract val libraryClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() = submit(GenerateAction::class.java) {
        it.decomposedDir.set(decomposedDir)
        it.componentName.set(componentName)
        it.packageName.set(packageName)
        it.internalPackage.set(internalPackage)
        it.backend.set(backend)
        it.world.set(world)
        it.interfaceMappings.set(interfaceMappings)
        it.libraryClasspath.from(libraryClasspath)
        it.outputDir.set(outputDir)
    }

    interface Params : WorkParameters {
        val decomposedDir: DirectoryProperty
        val componentName: Property<String>
        val packageName: Property<String>
        val internalPackage: Property<String>
        val backend: Property<String>
        val world: Property<String>
        val interfaceMappings: MapProperty<String, String>
        val libraryClasspath: ConfigurableFileCollection
        val outputDir: DirectoryProperty
    }

    abstract class GenerateAction : WorkAction<Params> {
        override fun execute() {
            val p = parameters
            val out = p.outputDir.get().asFile
            out.deleteRecursively()
            val decomposed = p.decomposedDir.get().asFile.toPath()
            if (p.backend.get() == "AOT") {
                val compiled = AotComponentCompiler.compileInMemory(decomposed, p.internalPackage.get())
                AotComponentCodegen.generate(
                    decomposedDir = decomposed,
                    outputDir = out.toPath(),
                    componentName = p.componentName.get(),
                    packageName = p.packageName.get(),
                    compiled = compiled,
                    internalPackage = p.internalPackage.get(),
                    worldName = p.world.orNull,
                    interfaceMappings = p.interfaceMappings.get(),
                    libraryClasspath = p.libraryClasspath.files.map { it.toPath() },
                )
            } else {
                ComponentCodegen.generate(
                    decomposedDir = decomposed,
                    outputDir = out.toPath(),
                    componentName = p.componentName.get(),
                    packageName = p.packageName.get(),
                    compiled = CoreModuleCompiler.layout(decomposed, p.internalPackage.get()),
                    planResource = "/" + p.internalPackage.get().replace('.', '/') + "/plan.json",
                    worldName = p.world.orNull,
                    interfaceMappings = p.interfaceMappings.get(),
                    libraryClasspath = p.libraryClasspath.files.map { it.toPath() },
                )
            }
        }
    }
}

/** 1980-02-01, the same constant Gradle uses for reproducible archives (zip has no earlier dates). */
private const val REPRODUCIBLE_TIMESTAMP = 318211200000L

/**
 * Prints the build-time warnings that a cacheable task recorded in [reports] (#39). The producing task
 * may be UP-TO-DATE or come FROM-CACHE, where its own action does not run; this task declares no
 * outputs, so it runs on every build that runs (or skips) the producing task, which it finalizes (#78).
 */
@DisableCachingByDefault(because = "Prints build-time warnings on every build, including when cacheable tasks are skipped.")
abstract class Wasm2KtWarningsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reports: ConfigurableFileCollection

    @TaskAction
    fun print() {
        for (file in reports.files) {
            if (file.isFile && file.length() > 0) logger.warn(file.readText().trimEnd())
        }
    }
}
