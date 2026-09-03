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
        // Where the actual ABL project (openedge-project.json, src/,
        // oepm-registries.properties, oepm.lock, oepm_packages/) lives.
        // Defaults to wherever build.gradle.kts itself is - identical to
        // today's behavior for any project that doesn't set this. Only
        // needs setting explicitly when Gradle's own project directory
        // isn't the ABL project root - e.g. a scaffolded project keeping
        // Gradle's own files in a .oepm/ subfolder sets this to file("..").
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
                    if (pendingAdd != null) {
                        val (packageName, versionSpec) = pendingAdd
                        manifest.dependencies + (packageName to DependencySpec.Registry(versionSpec))
                    } else {
                        manifest.dependencies
                    }

                // Resolves the full dependency graph, not just direct
                // dependencies — a resolved package's own declared
                // dependencies are resolved too, recursively (see
                // oepm.resolver.DependencyResolver). directSourceCacheDir
                // is only used for direct-source dependencies (inline
                // repoUrl/ref, no registry involved) - auto-created on
                // demand, same convention as the rest of the cache.
                val directSourceCacheDir = extension.cacheDir.get().asFile.resolve("_direct")
                val resolvedPackages = DependencyResolver.resolveAll(dependenciesToResolve, registry, directSourceCacheDir)

                // Verify every package against oepm.lock's existing entries
                // *before* touching oepm_packages/ - if a registry served
                // different content for an already-locked version (e.g. a
                // force-moved git tag), fail loudly before anything on
                // disk changes, not after. Hashed once here and reused
                // below when writing the new lockfile.
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
 * Registries come from two mergeable sources: the registries{} DSL block
 * (hand-authored, in build.gradle.kts) and oepm-registries.properties
 * (see RegistriesPropertiesFile - a second source specifically because
 * it's safe to programmatically append to, unlike an arbitrary existing
 * build.gradle.kts; oepmRegistryAdd and scaffoldProject both write to it).
 * A prefix declared in both sources - or twice in the same source - is a
 * duplicate-prefix error, not a silent pick.
 *
 * If neither source has any entries, behaves exactly as before
 * (LocalDirectoryRegistry against registryRoot) for backward compatibility.
 * registryRoot is never silently merged in alongside real registries, to
 * avoid an ambiguous "no prefix matched, fall back to local?" behavior.
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
