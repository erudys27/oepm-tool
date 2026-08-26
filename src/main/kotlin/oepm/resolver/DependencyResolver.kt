package oepm.resolver

import oepm.fetch.GitPackageFetcher
import oepm.manifest.DependencySpec
import oepm.manifest.ManifestReader
import oepm.registry.Registry
import oepm.registry.ResolvedPackage
import oepm.version.CaretRange
import oepm.version.SemVer
import java.io.File

/**
 * Resolves a manifest's dependencies transitively: each resolved package's
 * own openedge-project.json is read in turn, and its dependencies are
 * resolved too, recursively, until the whole graph is flat.
 *
 * Two dependency shapes (see oepm.manifest.DependencySpec):
 * - Registry: routed through the given Registry (PrefixRoutingRegistry,
 *   normally), exactly as before. The declared name must already be
 *   fully-qualified (e.g. "ba.greeter").
 * - DirectSource: fetched directly by repoUrl/ref (GitPackageFetcher), no
 *   registry lookup at all. When it's a *transitive* dependency (declared
 *   inside another package's own manifest), its key in the resolved graph
 *   inherits the enclosing package's own resolvedPrefix — a direct-source
 *   "greeter" declared by a "ba."-routed package becomes "ba.greeter"
 *   here, without ever being routed. A root-level direct-source
 *   dependency has no parent to inherit a prefix from, so it keeps its
 *   bare declared name.
 *
 * v1 rules (see docs/spec/lockfile-format.md): exactly one resolution per
 * key across the whole graph — a second, incompatible requirement for an
 * already-resolved key is an error, not a silent pick (no npm-style
 * "install both"). This now also covers two DirectSource specs disagreeing
 * on repoUrl/ref, and the same key being declared as both a Registry and a
 * DirectSource dependency somewhere in the graph. A true circular
 * dependency (A -> B -> A, neither finished resolving) is also an error,
 * not a silent short-circuit.
 *
 * A resolved package's *own* declared package_name (its real OO ABL
 * namespace - see oepm.manifest.Manifest) is independent of the key it was
 * resolved under (see DependencySpec.DirectSource's prefix-inheritance
 * above), so two different keys can end up resolving to packages sharing
 * the same real namespace (e.g. two independently-fetched "calculator"
 * packages, routed/declared under different keys). PROPATH is a single
 * flat, ordered list with no ambiguity detection of its own - whichever
 * one lands first on PROPATH silently shadows the other, with no compile
 * error. checkNoNamespaceCollision below catches this at resolve time
 * instead, once the whole graph is known, so it's a loud, named failure
 * rather than a silently-wrong PROPATH.
 */
object DependencyResolver {
    fun resolveAll(
        rootDependencies: Map<String, DependencySpec>,
        registry: Registry,
        directSourceCacheDir: File,
    ): Map<String, ResolvedPackage> {
        val resolved = LinkedHashMap<String, ResolvedPackage>()
        val resolvedSpecs = HashMap<String, DependencySpec>()
        val namespaceByKey = HashMap<String, String>()

        for ((packageName, spec) in rootDependencies) {
            resolveOne(packageName, spec, path = emptyList(), resolved, resolvedSpecs, namespaceByKey, registry, directSourceCacheDir)
        }

        checkNoNamespaceCollision(namespaceByKey)

        return resolved
    }

    private fun resolveOne(
        packageKey: String,
        spec: DependencySpec,
        path: List<String>,
        resolved: MutableMap<String, ResolvedPackage>,
        resolvedSpecs: MutableMap<String, DependencySpec>,
        namespaceByKey: MutableMap<String, String>,
        registry: Registry,
        directSourceCacheDir: File,
    ) {
        check(packageKey !in path) {
            "Circular dependency: ${(path + packageKey).joinToString(" -> ")}"
        }

        val existing = resolved[packageKey]
        if (existing != null) {
            checkNoConflict(packageKey, spec, existing, resolvedSpecs.getValue(packageKey), path)
            return
        }

        val resolvedPackage =
            when (spec) {
                is DependencySpec.Registry -> registry.resolve(packageKey, spec.versionSpec)
                is DependencySpec.DirectSource ->
                    GitPackageFetcher.fetch(packageKey, spec.repoUrl, spec.ref, File(directSourceCacheDir, packageKey))
            }
        resolved[packageKey] = resolvedPackage
        resolvedSpecs[packageKey] = spec

        val ownManifestFile = resolvedPackage.projectDir.resolve("openedge-project.json")
        if (ownManifestFile.exists()) {
            val ownManifest = ManifestReader.read(ownManifestFile)
            namespaceByKey[packageKey] = ownManifest.packageName
            for ((depName, depSpec) in ownManifest.dependencies) {
                val childKey =
                    when (depSpec) {
                        is DependencySpec.Registry -> depName
                        is DependencySpec.DirectSource -> (resolvedPackage.resolvedPrefix ?: "") + depName
                    }
                resolveOne(
                    childKey,
                    depSpec,
                    path + packageKey,
                    resolved,
                    resolvedSpecs,
                    namespaceByKey,
                    registry,
                    directSourceCacheDir,
                )
            }
        }
    }

    /**
     * Two different resolved keys sharing the same real package_name would
     * silently shadow each other on PROPATH (see class doc) - fail loudly
     * instead, before anything gets copied into oepm_packages/.
     */
    private fun checkNoNamespaceCollision(namespaceByKey: Map<String, String>) {
        val keysByNamespace = namespaceByKey.entries.groupBy({ it.value }, { it.key })
        for ((namespace, keys) in keysByNamespace) {
            check(keys.size == 1) {
                "PROPATH namespace collision: ${keys.sorted().joinToString(" and ")} both declare the same " +
                    "OO ABL namespace \"$namespace\" in their own package_name. Only one would actually be " +
                    "reachable on PROPATH (whichever comes first), silently shadowing the other. Rename one " +
                    "package's own package_name/namespace so they no longer collide."
            }
        }
    }

    private fun checkNoConflict(
        packageKey: String,
        spec: DependencySpec,
        existing: ResolvedPackage,
        existingSpec: DependencySpec,
        path: List<String>,
    ) {
        val via = (path + packageKey).joinToString(" -> ")
        when {
            spec is DependencySpec.Registry && existingSpec is DependencySpec.Registry -> {
                val existingVersion = SemVer.parse(existing.version)
                check(CaretRange.satisfies(spec.versionSpec, existingVersion)) {
                    "Version conflict for \"$packageKey\": already resolved to ${existing.version}, " +
                        "which does not satisfy ${spec.versionSpec} required via $via"
                }
            }
            spec is DependencySpec.DirectSource && existingSpec is DependencySpec.DirectSource -> {
                check(spec.repoUrl == existingSpec.repoUrl && spec.ref == existingSpec.ref) {
                    "Conflicting direct-source dependency for \"$packageKey\": already resolved from " +
                        "${existingSpec.repoUrl}@${existingSpec.ref}, but $via requires ${spec.repoUrl}@${spec.ref}"
                }
            }
            else ->
                throw IllegalStateException(
                    "Conflicting dependency kinds for \"$packageKey\": resolved once as a registry " +
                        "dependency and once as a direct-source dependency (via $via) — these must refer to " +
                        "the same kind of dependency.",
                )
        }
    }
}
