package oepm.resolver

import oepm.manifest.ManifestReader
import oepm.registry.Registry
import oepm.registry.ResolvedPackage
import oepm.version.CaretRange
import oepm.version.SemVer

/**
 * Resolves a manifest's dependencies transitively: each resolved package's
 * own openedge-project.json is read in turn, and its dependencies are
 * resolved too, recursively, until the whole graph is flat.
 *
 * v1 rules (see docs/spec/lockfile-format.md): exactly one version per
 * package name across the whole graph — a second, incompatible version
 * requirement for an already-resolved package name is an error, not a
 * silent pick (no npm-style "install both"). A true circular dependency
 * (A -> B -> A, neither finished resolving) is also an error, not a
 * silent short-circuit.
 */
object DependencyResolver {
    fun resolveAll(rootDependencies: Map<String, String>, registry: Registry): Map<String, ResolvedPackage> {
        val resolved = LinkedHashMap<String, ResolvedPackage>()

        for ((packageName, versionSpec) in rootDependencies) {
            resolveOne(packageName, versionSpec, path = emptyList(), resolved, registry)
        }

        return resolved
    }

    private fun resolveOne(
        packageName: String,
        versionSpec: String,
        path: List<String>,
        resolved: MutableMap<String, ResolvedPackage>,
        registry: Registry,
    ) {
        check(packageName !in path) {
            "Circular dependency: ${(path + packageName).joinToString(" -> ")}"
        }

        val existing = resolved[packageName]
        if (existing != null) {
            val existingVersion = SemVer.parse(existing.version)
            check(CaretRange.satisfies(versionSpec, existingVersion)) {
                "Version conflict for \"$packageName\": already resolved to ${existing.version}, " +
                    "which does not satisfy $versionSpec required via " +
                    "${(path + packageName).joinToString(" -> ")}"
            }
            return
        }

        val resolvedPackage = registry.resolve(packageName, versionSpec)
        resolved[packageName] = resolvedPackage

        val ownManifestFile = resolvedPackage.projectDir.resolve("openedge-project.json")
        if (ownManifestFile.exists()) {
            val ownManifest = ManifestReader.read(ownManifestFile)
            for ((depName, depVersionSpec) in ownManifest.dependencies) {
                resolveOne(depName, depVersionSpec, path + packageName, resolved, registry)
            }
        }
    }
}
