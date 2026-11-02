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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.CoreModuleCompiler
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.PlanCheck
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.inject.Inject

/**
 * Base for tasks that run wasm2kt's tooling. The tooling (Chicory's compiler, ASM, KotlinPoet, ...)
 * runs in an isolated class loader so it cannot clash with the Android Gradle plugin's own ASM.
 */
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

    /** Fail the build, instead of warning, when the component uses something the Chicory backend cannot run. */
    @get:Input
    abstract val failOnUnsupportedFeatures: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun decompose() = submit(DecomposeAction::class.java) {
        it.component.set(component)
        it.outputDir.set(outputDir)
        it.failOnUnsupportedFeatures.set(failOnUnsupportedFeatures)
    }

    interface Params : WorkParameters {
        val component: RegularFileProperty
        val outputDir: DirectoryProperty
        val failOnUnsupportedFeatures: Property<Boolean>
    }

    abstract class DecomposeAction : WorkAction<Params> {
        private val logger = org.gradle.api.logging.Logging.getLogger(Wasm2KtDecomposeTask::class.java)

        override fun execute() {
            val out = parameters.outputDir.get().asFile
            out.deleteRecursively()
            val component = parameters.component.get().asFile
            Decomposer.decompose(component.toPath(), out.toPath())

            // Report what would otherwise only fail on the device, the first time it is used (#39).
            val problems = PlanCheck.unsupportedFeatures(out.toPath())
            if (problems.isEmpty()) return
            val report = "wasm2kt: ${component.name} uses features the Chicory backend does not support yet; " +
                "calls that reach them will throw at run time:\n" + problems.joinToString("\n") { "  - $it" }
            if (parameters.failOnUnsupportedFeatures.get()) {
                out.deleteRecursively() // never leave a decomposed result behind for a failed check
                throw IllegalStateException("$report\n(set wasm2kt.failOnUnsupportedFeatures = false to build anyway)")
            }
            logger.warn("$report\n(set wasm2kt.failOnUnsupportedFeatures = true to fail the build instead)")
        }
    }
}

/**
 * Compiles the core modules to JVM bytecode and packages them, with their `.meta` modules and the
 * plan, into one jar that is added to the project's dependencies (dexed like any library on Android).
 */
@CacheableTask
abstract class Wasm2KtCompileTask : Wasm2KtToolingTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val decomposedDir: DirectoryProperty

    /** JVM package for the compiled classes and resources of this component. */
    @get:Input
    abstract val internalPackage: Property<String>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    @TaskAction
    fun compile() = submit(CompileAction::class.java) {
        it.decomposedDir.set(decomposedDir)
        it.internalPackage.set(internalPackage)
        it.jar.set(jar)
        it.workDir.set(temporaryDir)
    }

    interface Params : WorkParameters {
        val decomposedDir: DirectoryProperty
        val internalPackage: Property<String>
        val jar: RegularFileProperty
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
            val compiled = CoreModuleCompiler.compile(decomposed, classes, resources, pkg)
            ComponentBuild.copyPlan(decomposed, resources, pkg)
            compiled.filter { it.interpretedFunctions > 0 }.forEach {
                logger.warn("wasm2kt: ${it.interpretedFunctions} function(s) of core module ${it.index} are too large for a JVM method and will be interpreted")
            }
            writeJar(parameters.jar.get().asFile, listOf(classes, resources).map { it.toFile() })
        }

        private fun writeJar(target: File, roots: List<File>) {
            target.parentFile.mkdirs()
            JarOutputStream(Files.newOutputStream(target.toPath())).use { out ->
                for (root in roots.map { it.toPath() }) {
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

/** 1980-02-01, the same constant Gradle uses for reproducible archives (zip has no earlier dates). */
private const val REPRODUCIBLE_TIMESTAMP = 318211200000L
