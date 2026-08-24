package oepm

import oepm.manifest.BuildPathUpdater
import oepm.manifest.DependenciesUpdater
import oepm.manifest.ManifestReader
import oepm.propath.PropathGenerator
import oepm.registry.LocalDirectoryRegistry
import oepm.registry.Registry
import oepm.resolver.DependencyResolver
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.json.JSONObject

abstract class OepmExtension {
    abstract val registryRoot: DirectoryProperty
}

class OepmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("oepm", OepmExtension::class.java)
        extension.registryRoot.convention(project.layout.projectDirectory)

        project.tasks.register("oepmInstall") { task ->
            task.group = "oepm"
            task.description =
                "Resolves the project's declared dependencies. " +
                "Pass -PoepmAdd=<package_name>[:<versionSpec>] to add and resolve a new dependency in one step."
            task.doLast {
                val manifestFile = project.projectDir.resolve("openedge-project.json")
                val registry = LocalDirectoryRegistry(extension.registryRoot.get().asFile)
                val manifest = ManifestReader.read(manifestFile)

                // -PoepmAdd is only resolved to a (packageName, versionSpec)
                // pair here — nothing is written to the manifest yet. It's
                // merged into the dependency set purely in memory so
                // DependencyResolver sees it, keeping the whole operation
                // all-or-nothing: if resolution fails (e.g. a version
                // conflict), the manifest's dependencies are never touched,
                // same as oepm_packages/oepm.lock/buildPath below.
                val pendingAdd: Pair<String, String>? =
                    if (project.hasProperty("oepmAdd")) {
                        resolveAddSpec(project.property("oepmAdd") as String, registry)
                    } else {
                        null
                    }
                val dependenciesToResolve =
                    if (pendingAdd != null) manifest.dependencies + pendingAdd else manifest.dependencies

                // Resolves the full dependency graph, not just direct
                // dependencies — a resolved package's own declared
                // dependencies are resolved too, recursively (see
                // oepm.resolver.DependencyResolver).
                val resolved =
                    DependencyResolver.resolveAll(dependenciesToResolve, registry).mapValues { (packageName, resolvedPackage) ->
                        val destination =
                            project.projectDir
                                .resolve("oepm_packages")
                                .resolve(packageName)
                                .resolve("src")
                        destination.deleteRecursively()
                        resolvedPackage.sourceDir.copyRecursively(destination, overwrite = true)
                        resolvedPackage
                    }

                if (pendingAdd != null) {
                    val (packageName, versionSpec) = pendingAdd
                    DependenciesUpdater.addDependency(manifestFile, packageName, versionSpec)
                    project.logger.lifecycle("oepm install: added \"$packageName\": \"$versionSpec\" to dependencies")
                }

                val resolvedJson = JSONObject()
                resolved.forEach { (packageName, resolvedPackage) ->
                    resolvedJson.put(
                        packageName,
                        JSONObject()
                            .put("version", resolvedPackage.version)
                            .put("source", resolvedPackage.sourceDir.absolutePath)
                            .put("integrity", "sha256:NOT-YET-IMPLEMENTED"),
                    )
                }
                val lockJson = JSONObject().put("resolved", resolvedJson)
                project.projectDir.resolve("oepm.lock").writeText(lockJson.toString(2))

                val dependencySourcePaths = resolved.keys.map { packageName -> "oepm_packages/$packageName/src" }
                BuildPathUpdater.ensureSourceEntries(manifestFile, dependencySourcePaths)

                project.logger.lifecycle("oepm install: resolved ${resolved.size} dependencies")
            }
        }

        project.tasks.register("oepmPropath") { task ->
            task.group = "oepm"
            task.description = "Prints the generated PROPATH for the project."
            task.doLast {
                val manifest = ManifestReader.read(project.projectDir.resolve("openedge-project.json"))
                val propath = PropathGenerator.generate(project.projectDir, manifest)
                project.logger.lifecycle(propath.joinToString(System.lineSeparator()))
            }
        }
    }
}

/**
 * Parses -PoepmAdd=<package_name>[:<versionSpec>] into a (packageName,
 * versionSpec) pair, without writing anything. When no versionSpec is
 * given, the package is looked up in the registry and its actual version
 * is turned into a caret range (npm's `npm install <pkg>` behavior — pick
 * whatever's available, pin it as a caret range). The caller is
 * responsible for persisting this via DependenciesUpdater only once
 * resolution has actually succeeded.
 */
private fun resolveAddSpec(addSpec: String, registry: Registry): Pair<String, String> {
    val separatorIndex = addSpec.indexOf(':')
    val packageName = if (separatorIndex >= 0) addSpec.substring(0, separatorIndex) else addSpec
    val versionSpec =
        if (separatorIndex >= 0) {
            addSpec.substring(separatorIndex + 1)
        } else {
            val found =
                registry.findAny(packageName)
                    ?: throw IllegalStateException("No package named \"$packageName\" found in the registry")
            "^${found.version}"
        }

    return packageName to versionSpec
}
