package wasm2kt.gradle

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.DomainObjectSet
import org.gradle.api.tasks.TaskProvider
import java.util.Properties
import javax.inject.Inject

/**
 * ```
 * wasm2kt {
 *     components {
 *         register("calculator") {
 *             source = file("src/main/wasm/calculator.wasm")
 *             packageName = "com.example.calculator"
 *         }
 *     }
 * }
 * ```
 */
abstract class Wasm2KtExtension @Inject constructor(project: Project) {
    val components: NamedDomainObjectContainer<Wasm2KtComponent> =
        project.objects.domainObjectContainer(Wasm2KtComponent::class.java)

    /**
     * Adds the matching `io.github.wasm2kt:runtime-chicory` dependency to `implementation`.
     * Turn off to manage the runtime dependency yourself.
     */
    abstract val addRuntimeDependency: Property<Boolean>

    /**
     * Fail the build when a component uses features the Chicory backend does not support yet
     * (component intrinsics, types or async). Off by default: the build only warns, and calls that
     * reach those features throw at run time.
     */
    abstract val failOnUnsupportedFeatures: Property<Boolean>

    /**
     * Use the interfaces that libraries on the compile class path provide (their wasm2kt interface
     * manifests) instead of generating them, when a component imports the same interface and version
     * (ADR-0014). On by default; explicit [Wasm2KtComponent.with] mappings always apply.
     */
    abstract val useLibraryInterfaces: Property<Boolean>

    fun components(action: org.gradle.api.Action<NamedDomainObjectContainer<Wasm2KtComponent>>) = action.execute(components)
}

abstract class Wasm2KtComponent @Inject constructor(private val name: String) : Named {
    override fun getName(): String = name

    /** The `.wasm` component binary. */
    abstract val source: RegularFileProperty

    /** Kotlin package of the generated bindings and `<Name>Component`. */
    abstract val packageName: Property<String>

    /** World to generate when the component's WIT defines several; defaults to the component's own world. */
    abstract val world: Property<String>

    /** Imported interfaces (`wasi:filesystem/types@0.3.0`) mapped to the Kotlin package of existing bindings (ADR-0014). */
    abstract val interfaceMappings: MapProperty<String, String>

    /**
     * Uses the bindings already generated into [packageName] for the imported interface [interfaceName]
     * (fully qualified, with its version) instead of generating it, like wit-bindgen's `with` (ADR-0014).
     */
    fun with(interfaceName: String, packageName: String) {
        interfaceMappings.put(interfaceName, packageName)
    }
}

class Wasm2KtPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("wasm2kt", Wasm2KtExtension::class.java, project)
        extension.addRuntimeDependency.convention(true)
        extension.failOnUnsupportedFeatures.convention(false)
        extension.useLibraryInterfaces.convention(true)

        // The tooling runs in workers with its own class path (see Wasm2KtToolingTask).
        val tooling = project.configurations.create(TOOLING_CONFIGURATION) { c ->
            c.isCanBeConsumed = false
            c.isCanBeResolved = true
            c.isVisible = false
            c.description = "wasm2kt build-time tooling (decomposer, Chicory compiler, code generator)."
            c.defaultDependencies { it.add(project.dependencies.create("io.github.wasm2kt:tooling:${pluginVersion()}")) }
        }
        project.tasks.withType(Wasm2KtToolingTask::class.java).configureEach { it.toolingClasspath.from(tooling) }

        val wired = project.objects.domainObjectSet(ComponentTasks::class.java)
        extension.components.all { component -> wired.add(registerTasks(project, extension, component)) }

        project.afterEvaluate {
            // Only projects with a JVM or Android plugin have `implementation`; without one there is
            // nothing to compile against, but the wasm2kt tasks still work on their own.
            if (extension.addRuntimeDependency.get() && extension.components.isNotEmpty() &&
                project.configurations.findByName("implementation") != null
            ) {
                project.dependencies.add("implementation", "io.github.wasm2kt:runtime-chicory:${pluginVersion()}")
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            KotlinJvmWiring.wire(project, extension, wired)
        }
        for (id in listOf("com.android.application", "com.android.library")) {
            project.pluginManager.withPlugin(id) { AndroidWiring.wire(project, extension, wired) }
        }
    }

    private fun registerTasks(project: Project, extension: Wasm2KtExtension, component: Wasm2KtComponent): ComponentTasks {
        val cap = component.name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val base = project.layout.buildDirectory.dir("wasm2kt/${component.name}")
        val internalPackage = component.packageName.map { internalPackage(it, component.name) }

        val decompose = project.tasks.register("wasm2ktDecompose$cap", Wasm2KtDecomposeTask::class.java) { t ->
            t.group = GROUP
            t.description = "Decomposes the '${component.name}' WebAssembly component into core modules."
            t.component.set(component.source)
            t.failOnUnsupportedFeatures.set(extension.failOnUnsupportedFeatures)
            t.outputDir.set(base.map { it.dir("decomposed") })
        }
        val compile = project.tasks.register("wasm2ktCompile$cap", Wasm2KtCompileTask::class.java) { t ->
            t.group = GROUP
            t.description = "Compiles the '${component.name}' core modules to JVM bytecode."
            t.decomposedDir.set(decompose.flatMap { it.outputDir })
            t.internalPackage.set(internalPackage)
            t.jar.set(base.map { it.file("wasm2kt-${component.name}.jar") })
        }
        val generate = project.tasks.register("wasm2ktGenerate$cap", Wasm2KtGenerateTask::class.java) { t ->
            t.group = GROUP
            t.description = "Generates Kotlin bindings for the '${component.name}' WebAssembly component."
            t.decomposedDir.set(decompose.flatMap { it.outputDir })
            t.componentName.set(component.name)
            t.packageName.set(component.packageName)
            t.internalPackage.set(internalPackage)
            t.world.set(component.world)
            t.interfaceMappings.set(component.interfaceMappings)
            t.outputDir.set(base.map { it.dir("kotlin") })
        }
        return ComponentTasks(component, decompose, compile, generate)
    }

    /** Must match `wasm2kt.tooling.ComponentBuild.internalPackage` (the tooling isn't on this class path). */
    private fun internalPackage(packageName: String, componentName: String): String =
        "$packageName.wasm2kt." + componentName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    internal fun pluginVersion(): String {
        val props = Properties()
        Wasm2KtPlugin::class.java.getResourceAsStream("/wasm2kt/gradle/wasm2kt-plugin.properties")?.use { props.load(it) }
        return props.getProperty("version") ?: error("wasm2kt plugin version resource missing")
    }

    companion object {
        const val GROUP = "wasm2kt"
        const val TOOLING_CONFIGURATION = "wasm2ktTooling"
    }
}

class ComponentTasks(
    val component: Wasm2KtComponent,
    val decompose: TaskProvider<Wasm2KtDecomposeTask>,
    val compile: TaskProvider<Wasm2KtCompileTask>,
    val generate: TaskProvider<Wasm2KtGenerateTask>,
)
