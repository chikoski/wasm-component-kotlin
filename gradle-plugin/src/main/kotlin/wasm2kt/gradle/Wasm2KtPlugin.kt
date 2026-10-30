package wasm2kt.gradle

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.DomainObjectSet
import org.gradle.api.tasks.TaskProvider
import wasm2kt.tooling.ComponentBuild
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
}

class Wasm2KtPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("wasm2kt", Wasm2KtExtension::class.java, project)
        extension.addRuntimeDependency.convention(true)

        val wired = project.objects.domainObjectSet(ComponentTasks::class.java)
        extension.components.all { component -> wired.add(registerTasks(project, component)) }

        project.afterEvaluate {
            if (extension.addRuntimeDependency.get() && extension.components.isNotEmpty()) {
                project.dependencies.add("implementation", "io.github.wasm2kt:runtime-chicory:${pluginVersion()}")
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            KotlinJvmWiring.wire(project, wired)
        }
        for (id in listOf("com.android.application", "com.android.library")) {
            project.pluginManager.withPlugin(id) { AndroidWiring.wire(project, wired) }
        }
    }

    private fun registerTasks(project: Project, component: Wasm2KtComponent): ComponentTasks {
        val cap = component.name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val base = project.layout.buildDirectory.dir("wasm2kt/${component.name}")
        val internalPackage = component.packageName.map { ComponentBuild.internalPackage(it, component.name) }

        val decompose = project.tasks.register("wasm2ktDecompose$cap", Wasm2KtDecomposeTask::class.java) { t ->
            t.group = GROUP
            t.description = "Decomposes the '${component.name}' WebAssembly component into core modules."
            t.component.set(component.source)
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
            t.outputDir.set(base.map { it.dir("kotlin") })
        }
        return ComponentTasks(component, decompose, compile, generate)
    }

    private fun pluginVersion(): String {
        val props = Properties()
        Wasm2KtPlugin::class.java.getResourceAsStream("/wasm2kt/gradle/wasm2kt-plugin.properties")?.use { props.load(it) }
        return props.getProperty("version") ?: error("wasm2kt plugin version resource missing")
    }

    companion object {
        const val GROUP = "wasm2kt"
    }
}

class ComponentTasks(
    val component: Wasm2KtComponent,
    val decompose: TaskProvider<Wasm2KtDecomposeTask>,
    val compile: TaskProvider<Wasm2KtCompileTask>,
    val generate: TaskProvider<Wasm2KtGenerateTask>,
)
