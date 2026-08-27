package oepm.fetch

import oepm.manifest.ManifestReader
import oepm.registry.ResolvedPackage
import java.io.File

/**
 * Shallow-clones one dedicated package repo at a given ref and builds a
 * ResolvedPackage from its own openedge-project.json. Shared by
 * CatalogRegistry (once it's found a repoUrl/ref via its catalog) and
 * oepm.resolver.DependencyResolver's direct-source dependency path (which
 * gets repoUrl/ref straight from a package's own manifest, no registry
 * involved at all) - the fetch mechanism is identical either way, only
 * *how* repoUrl/ref were found differs.
 */
object GitPackageFetcher {
    fun fetch(packageName: String, repoUrl: String, ref: String, destDir: File): ResolvedPackage {
        ensureCloned(packageName, repoUrl, ref, destDir)

        val manifestFile = File(destDir, "openedge-project.json")
        require(manifestFile.exists()) {
            "\"$packageName\" was fetched from $repoUrl (ref $ref) into ${destDir.path}, " +
                "but it has no openedge-project.json"
        }
        val manifest = ManifestReader.read(manifestFile)

        val packageRoot =
            manifest.sourceRoots.firstOrNull()
                ?: throw IllegalStateException(
                    "\"$packageName\" at ${destDir.path} has no buildPath source entry to serve as its package_root",
                )

        return ResolvedPackage(
            packageName = packageName,
            version = manifest.version,
            sourceDir = File(destDir, packageRoot),
            projectDir = destDir,
        )
    }

    private fun ensureCloned(packageName: String, repoUrl: String, ref: String, destDir: File) {
        if (File(destDir, ".git").exists()) return

        destDir.parentFile?.mkdirs()
        try {
            GitCli.run(null, "clone", "--depth", "1", "--branch", ref, repoUrl, destDir.path)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to fetch \"$packageName\" from $repoUrl at ref \"$ref\": ${e.message}",
                e,
            )
        }
    }
}
