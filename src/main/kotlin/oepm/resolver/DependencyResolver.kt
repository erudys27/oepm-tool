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
 *   registry lookup at all. Always keyed by its own bare declared name -
 *   no inherited prefix, root-level or transitive. This is deliberate:
 *   two packages resolved via *different* registries that both declare a
 *   direct-source dependency on the exact same repoUrl/ref (a shared
 *   utility package) end up requesting the *same* key, so the reuse/
 *   conflict logic below (an exact repoUrl/ref match on an already-
 *   resolved key is fine, a mismatch is a named conflict) naturally
 *   dedupes them instead of every parent minting its own copy under a
 *   different prefixed key. Inheriting a prefix here was tried and
 *   dropped (2026-08-26) - it made that shared-dependency case trip
 *   checkNoNamespaceCollision below as a false positive (same real
 *   package_name, different keys), even though nothing was actually
 *   wrong. Two direct-source deps that really are different things just
 *   happening to share a bare name are still caught correctly - by the
 *   repoUrl/ref mismatch check, which is more precise than a
 *   namespace-based check would be.
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
 * resolved under, so two different keys can still end up resolving to
 * packages sharing the same real namespace (e.g. two independently
 * registry-routed "calculator" packages under different prefixes). PROPATH
 * is a single flat, ordered list with no ambiguity detection of its own -
 * whichever one lands first silently shadows the other, with no compile
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
                    GitPackageFetcher
                        .fetch(packageKey, spec.repoUrl, spec.ref, File(directSourceCacheDir, packageKey))
                        .copy(installSubpath = "_direct/$packageKey")
            }
        resolved[packageKey] = resolvedPackage
        resolvedSpecs[packageKey] = spec

        val ownManifestFile = resolvedPackage.projectDir.resolve("openedge-project.json")
        if (ownManifestFile.exists()) {
            val ownManifest = ManifestReader.read(ownManifestFile)
            namespaceByKey[packageKey] = ownManifest.packageName
            for ((depName, depSpec) in ownManifest.dependencies) {
                resolveOne(
                    depName,
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
