package oepm

import oepm.manifest.BuildPathUpdater
import oepm.manifest.DependenciesUpdater
import oepm.manifest.ManifestReader
import oepm.propath.PropathGenerator
import oepm.registry.CatalogRegistry
import oepm.registry.LocalDirectoryRegistry
import oepm.registry.PrefixRoutingRegistry
import oepm.registry.Registry
import oepm.resolver.DependencyResolver
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * One remote, catalog-backed registry configuration entry — see
 * oepm.registry.CatalogRegistry. "name" is just the DSL entry's own label
 * (e.g. "ba"); "prefix" is the actual routing key matched against
 * package_name (e.g. "ba.").
 */
abstract class GitRegistrySpec
    @Inject
    constructor(private val entryName: String) : Named {
        abstract val prefix: Property<String>
        abstract val catalogUrl: Property<String>
        abstract val catalogRef: Property<String>

        override fun getName() = entryName
    }

abstract class OepmExtension
    @Inject
    constructor(objects: ObjectFactory) {
        abstract val registryRoot: DirectoryProperty
        abstract val cacheDir: DirectoryProperty

        val registries: NamedDomainObjectContainer<GitRegistrySpec> =
            objects.domainObjectContainer(GitRegistrySpec::class.java) { name ->
                objects.newInstance(GitRegistrySpec::class.java, name)
            }
    }

class OepmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("oepm", OepmExtension::class.java)
        extension.registryRoot.convention(project.layout.projectDirectory)
        extension.cacheDir.convention(
            project.layout.dir(
                project.provider {
                    File(
                        project.findProperty("oepmCacheDir") as? String
                            ?: "${System.getProperty("user.home")}/.oepm/cache",
                    )
                },
            ),
        )

        project.tasks.register("oepmInstall") { task ->
            task.group = "oepm"
            task.description =
                "Resolves the project's declared dependencies. " +
                "Pass -PoepmAdd=<package_name>[:<versionSpec>] to add and resolve a new dependency in one step."
            task.doLast {
                val manifestFile = project.projectDir.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
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
 * If no remote registries are configured, behaves exactly as before
 * (LocalDirectoryRegistry against registryRoot) for backward compatibility.
 * Otherwise builds a PrefixRoutingRegistry over the configured registries
 * — registryRoot is not silently merged in, to avoid an ambiguous
 * "no prefix matched, fall back to local?" behavior.
 */
private fun buildRegistry(extension: OepmExtension): Registry {
    if (extension.registries.isEmpty()) {
        return LocalDirectoryRegistry(extension.registryRoot.get().asFile)
    }

    val cacheRoot = extension.cacheDir.get().asFile
    val ownerByPrefix = LinkedHashMap<String, String>()
    val delegatesByPrefix = LinkedHashMap<String, Registry>()
    for (spec in extension.registries) {
        val prefix = spec.prefix.get()
        val existingOwner = ownerByPrefix[prefix]
        require(existingOwner == null) {
            "Duplicate registry prefix \"$prefix\": both \"$existingOwner\" and \"${spec.name}\" declare it"
        }
        ownerByPrefix[prefix] = spec.name

        delegatesByPrefix[prefix] =
            CatalogRegistry(
                registryName = spec.name,
                catalogUrl = spec.catalogUrl.get(),
                catalogRef = spec.catalogRef.getOrElse("main"),
                cacheDir = File(cacheRoot, spec.name),
            )
    }

    return PrefixRoutingRegistry(delegatesByPrefix)
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
