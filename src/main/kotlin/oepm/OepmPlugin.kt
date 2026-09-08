package oepm

import oepm.integrity.DirectoryHash
import oepm.lock.IntegrityChecker
import oepm.lock.LockfileReader
import oepm.manifest.BuildPathUpdater
import oepm.manifest.DependenciesUpdater
import oepm.manifest.DependencySpec
import oepm.manifest.ManifestReader
import oepm.propath.PropathGenerator
import oepm.registry.CatalogRegistry
import oepm.registry.LocalDirectoryRegistry
import oepm.registry.PrefixRoutingRegistry
import oepm.registry.Registry
import oepm.registry.RegistriesPropertiesFile
import oepm.resolver.DependencyResolver
import org.gradle.api.GradleException
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

/** One registries{} entry. "name" is just its DSL label; "prefix" is the actual routing key (e.g. "ba."). */
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
        // Where the ABL project lives. Defaults to build.gradle.kts's own
        // directory; set to file("..") when Gradle's files sit in .oepm/.
        abstract val projectRoot: DirectoryProperty
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
        extension.projectRoot.convention(project.layout.projectDirectory)
        extension.registryRoot.convention(extension.projectRoot)
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
                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)

                // Held in memory, not written yet - keeps a failed resolve from touching the manifest at all.
                val pendingAdd: Pair<String, String>? =
                    if (project.hasProperty("oepmAdd")) {
                        resolveAddSpec(project.property("oepmAdd") as String, registry)
                    } else {
                        null
                    }
                val dependenciesToResolve =
                    if (pendingAdd != null) {
                        val (packageName, versionSpec) = pendingAdd
                        manifest.dependencies + (packageName to DependencySpec.Registry(versionSpec))
                    } else {
                        manifest.dependencies
                    }

                // Resolves the full graph, including transitive deps - see DependencyResolver.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(dependenciesToResolve, registry, directSourceCacheDir)

                // Catches a moved/hijacked tag before touching oepm_packages/, not after.
                val existingLock = LockfileReader.read(projectRoot.resolve("oepm.lock"))
                val integrities =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        val integrity = DirectoryHash.hash(resolvedPackage.sourceDir)
                        IntegrityChecker.verify(packageName, resolvedPackage.version, integrity, existingLock)
                        integrity
                    }

                val resolved =
                    resolvedPackages.mapValues { (packageName, resolvedPackage) ->
                        val destination =
                            projectRoot
                                .resolve("oepm_packages")
                                .resolve(resolvedPackage.installSubpath ?: packageName)
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
                            .put("integrity", integrities.getValue(packageName)),
                    )
                }
                val lockJson = JSONObject().put("resolved", resolvedJson)
                projectRoot.resolve("oepm.lock").writeText(lockJson.toString(2))

                val dependencySourcePaths =
                    resolved.map { (packageName, resolvedPackage) ->
                        "oepm_packages/${resolvedPackage.installSubpath ?: packageName}/src"
                    }
                BuildPathUpdater.ensureSourceEntries(manifestFile, dependencySourcePaths)

                project.logger.lifecycle("oepm install: resolved ${resolved.size} dependencies")
            }
        }

        project.tasks.register("oepmPropath") { task ->
            task.group = "oepm"
            task.description =
                "Prints the generated PROPATH for the project. " +
                "Pass -PoepmIncludeTests to also include buildPath's \"test\" entries."
            task.doLast {
                val projectRoot = extension.projectRoot.get().asFile
                val manifest = ManifestReader.read(projectRoot.resolve("openedge-project.json"))
                val includeTests = project.hasProperty("oepmIncludeTests")
                val propath = PropathGenerator.generate(projectRoot, manifest, includeTests)
                project.logger.lifecycle(propath.joinToString(System.lineSeparator()))
            }
        }

        project.tasks.register("oepmPrune") { task ->
            task.group = "oepm"
            task.description =
                "Removes oepm_packages/ entries (and their buildPath references) that are no longer " +
                "part of the resolved dependency graph. Pass -PoepmDryRun to preview without changing anything."
            task.doLast {
                val projectRoot = extension.projectRoot.get().asFile
                val manifestFile = projectRoot.resolve("openedge-project.json")
                val registry = buildRegistry(extension)
                val manifest = ManifestReader.read(manifestFile)

                // Same resolution oepmInstall does, to know what "stale" actually means right now.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(manifest.dependencies, registry, directSourceCacheDir)
                val expectedPaths =
                    resolvedPackages.map { (packageName, resolvedPackage) ->
                        "oepm_packages/${resolvedPackage.installSubpath ?: packageName}/src"
                    }.toSet()

                val dryRun = project.hasProperty("oepmDryRun")

                val staleDirs = findStaleOepmPackagesDirs(projectRoot, expectedPaths)
                if (!dryRun) {
                    val oepmPackagesDir = projectRoot.resolve("oepm_packages")
                    staleDirs.forEach { (leafDir, _) ->
                        leafDir.deleteRecursively()
                        removeNowEmptyAncestors(leafDir.parentFile, oepmPackagesDir)
                    }
                }
                val staleBuildPathPaths = BuildPathUpdater.pruneStaleOepmPackagesEntries(manifestFile, expectedPaths, dryRun)

                val allStalePaths = (staleDirs.map { it.second } + staleBuildPathPaths).toSortedSet()
                if (allStalePaths.isEmpty()) {
                    project.logger.lifecycle("oepm prune: nothing to remove")
                } else {
                    val verb = if (dryRun) "would remove" else "removed"
                    allStalePaths.forEach { project.logger.lifecycle("oepm prune: $verb $it") }
                    val suffix = if (dryRun) " (dry run - nothing changed)" else ""
                    project.logger.lifecycle("oepm prune: $verb ${allStalePaths.size} entr${if (allStalePaths.size == 1) "y" else "ies"}$suffix")
                }
            }
        }

        project.tasks.register("oepmRegistryAdd") { task ->
            task.group = "oepm"
            task.description =
                "Adds a registry entry to oepm-registries.properties. " +
                "Usage: -PregistryPrefix=<prefix> -PcatalogUrl=<url> [-PregistryName=<name>]"
            task.doLast {
                val prefix =
                    project.findProperty("registryPrefix") as String?
                        ?: throw GradleException(
                            "Missing -PregistryPrefix=<prefix>. Usage: -PregistryPrefix=<prefix> " +
                                "-PcatalogUrl=<url> [-PregistryName=<name>]",
                        )
                val catalogUrl =
                    project.findProperty("catalogUrl") as String?
                        ?: throw GradleException(
                            "Missing -PcatalogUrl=<url>. Usage: -PregistryPrefix=<prefix> " +
                                "-PcatalogUrl=<url> [-PregistryName=<name>]",
                        )
                val name = project.findProperty("registryName") as String? ?: prefix.trimEnd('.')

                val file = extension.projectRoot.get().asFile.resolve("oepm-registries.properties")
                RegistriesPropertiesFile.add(file, name, prefix, catalogUrl)
                project.logger.lifecycle("oepm registry add: added \"$name\" ($prefix -> $catalogUrl) to ${file.name}")
            }
        }
    }
}

/**
 * Merges registries{} (build.gradle.kts) with oepm-registries.properties
 * (the programmatically-appendable source - see RegistriesPropertiesFile).
 * A prefix declared twice, in either source, is an error, not a pick.
 * Falls back to LocalDirectoryRegistry only if both sources are empty.
 */
private fun buildRegistry(extension: OepmExtension): Registry {
    val fileEntries = RegistriesPropertiesFile.read(extension.projectRoot.get().asFile.resolve("oepm-registries.properties"))

    if (extension.registries.isEmpty() && fileEntries.isEmpty()) {
        return LocalDirectoryRegistry(extension.registryRoot.get().asFile)
    }

    val cacheRoot = extension.cacheDir.get().asFile
    val ownerByPrefix = LinkedHashMap<String, String>()
    val delegatesByPrefix = LinkedHashMap<String, Registry>()

    fun addEntry(name: String, prefix: String, catalogUrl: String, catalogRef: String) {
        val existingOwner = ownerByPrefix[prefix]
        require(existingOwner == null) {
            "Duplicate registry prefix \"$prefix\": both \"$existingOwner\" and \"$name\" declare it"
        }
        ownerByPrefix[prefix] = name
        delegatesByPrefix[prefix] =
            CatalogRegistry(
                registryName = name,
                prefix = prefix,
                catalogUrl = catalogUrl,
                catalogRef = catalogRef,
                cacheDir = File(cacheRoot, name),
            )
    }

    for (spec in extension.registries) {
        addEntry(spec.name, spec.prefix.get(), spec.catalogUrl.get(), spec.catalogRef.getOrElse("main"))
    }
    for (entry in fileEntries) {
        addEntry(entry.name, entry.prefix, entry.catalogUrl, entry.catalogRef ?: "main")
    }

    return PrefixRoutingRegistry(delegatesByPrefix)
}

/** Every "src" dir under oepm_packages/ not in expectedPaths, paired with its parent (the whole package folder to delete). */
private fun findStaleOepmPackagesDirs(projectRoot: File, expectedPaths: Set<String>): List<Pair<File, String>> {
    val oepmPackagesDir = projectRoot.resolve("oepm_packages")
    if (!oepmPackagesDir.isDirectory) return emptyList()

    return oepmPackagesDir
        .walkTopDown()
        .filter { it.isDirectory && it.name == "src" }
        .mapNotNull { srcDir ->
            val relativePath = srcDir.relativeTo(projectRoot).invariantSeparatorsPath
            if (relativePath in expectedPaths) null else srcDir.parentFile to relativePath
        }.toList()
}

/** Deletes now-empty ancestor dirs (e.g. an emptied-out registry-prefix folder), stopping at stopAt or the first non-empty one. */
private fun removeNowEmptyAncestors(dir: File, stopAt: File) {
    var current = dir
    while (current.absolutePath != stopAt.absolutePath && current.isDirectory && current.listFiles().isNullOrEmpty()) {
        val parent = current.parentFile
        current.delete()
        current = parent
    }
}

/** Parses -PoepmAdd=<name>[:<versionSpec>]; no versionSpec means "whatever's available", pinned as ^version. */
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
