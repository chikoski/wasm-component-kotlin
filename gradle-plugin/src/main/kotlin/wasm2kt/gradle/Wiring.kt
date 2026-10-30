package wasm2kt.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.SourceSetContainer

/**
 * Kotlin/JVM: generated sources join `main`, the compiled-modules jar joins `implementation`.
 *
 * Only Gradle API types are used here: the Kotlin plugin's classes are not always visible to this
 * plugin's class loader (e.g. when the plugins are declared in different build scripts).
 */
internal object KotlinJvmWiring {
    fun wire(project: Project, components: DomainObjectSet<ComponentTasks>) {
        enableContextParameters(project)
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val kotlinSources = main.extensions.getByName("kotlin") as SourceDirectorySet
        components.all { c ->
            kotlinSources.srcDir(c.generate.flatMap { it.outputDir })
            project.dependencies.add("implementation", project.files(c.compile.flatMap { it.jar }))
        }
    }
}

/**
 * Android: the compiled-modules jar is an `implementation` file dependency (dexed and shrunk like
 * any library). Generated sources are registered per variant through the Variant API, which owns
 * the output location, so each variant gets its own (cheap) generate task.
 */
internal object AndroidWiring {
    fun wire(project: Project, components: DomainObjectSet<ComponentTasks>) {
        components.all { c -> project.dependencies.add("implementation", project.files(c.compile.flatMap { it.jar })) }
        // Covers both the kotlin-android plugin and AGP 9's built-in Kotlin support.
        enableContextParameters(project)

        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            val variantCap = variant.name.replaceFirstChar(Char::uppercaseChar)
            for (c in components) {
                val shared = c.generate
                val perVariant = project.tasks.register("${shared.name}$variantCap", Wasm2KtGenerateTask::class.java) { t ->
                    t.group = Wasm2KtPlugin.GROUP
                    t.description = "Generates Kotlin bindings for the '${c.component.name}' component (${variant.name})."
                    t.decomposedDir.set(c.decompose.flatMap { it.outputDir })
                    t.componentName.set(c.component.name)
                    t.packageName.set(c.component.packageName)
                    t.internalPackage.set(shared.flatMap { it.internalPackage })
                    t.world.set(c.component.world)
                }
                // Kotlin compilations read the variant's Java source directories; `sources.kotlin` is not
                // consumed by every AGP/KGP combination (e.g. AGP 8.9 + KGP 2.2).
                val sources = variant.sources.java
                    ?: error("Variant ${variant.name} has no Java sources to add wasm2kt bindings to")
                sources.addGeneratedSourceDirectory(perVariant, Wasm2KtGenerateTask::outputDir)
            }
        }
    }
}

/**
 * The generated world DSL uses context parameters, still behind a flag in Kotlin 2.2. Configured
 * through the `kotlin` extension reflectively, for the same class-loader reason as above.
 */
internal fun enableContextParameters(project: Project) {
    project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { addContextParametersFlag(project) }
    project.pluginManager.withPlugin("org.jetbrains.kotlin.android") { addContextParametersFlag(project) }
    project.pluginManager.withPlugin("com.android.built-in-kotlin") { addContextParametersFlag(project) }
}

private fun addContextParametersFlag(project: Project) {
    val kotlin = project.extensions.findByName("kotlin") ?: return
    try {
        val options = kotlin.javaClass.getMethod("getCompilerOptions").invoke(kotlin)
        @Suppress("UNCHECKED_CAST")
        val args = options.javaClass.getMethod("getFreeCompilerArgs").invoke(options) as ListProperty<String>
        args.add("-Xcontext-parameters")
    } catch (e: ReflectiveOperationException) {
        project.logger.warn("wasm2kt: could not enable -Xcontext-parameters (${e.javaClass.simpleName}); add it to your Kotlin compiler options")
    }
}
