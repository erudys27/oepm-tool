package oepm.registry

import oepm.fetch.GitCli
import oepm.manifest.ManifestReader
import oepm.version.CaretRange
import oepm.version.SemVer
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Registry backed by a small "catalog" git repo that holds no package
 * content itself — only one reference file per package
 * (packages/<package_name>.json) pointing at that package's own dedicated
 * repo URL + tag. Fetching a package is a plain shallow clone of that
 * dedicated repo, no partial-clone machinery involved: the catalog is
 * small enough to clone in full, and each package repo is small and
 * dedicated to one package.
 *
 * Cache layout under cacheDir (one CatalogRegistry per configured
 * registry, so cacheDir is already scoped to this registry's name):
 *   _catalog/                 full clone of the catalog repo
 *   <package_name>/           shallow clone of that package's own repo
 */
class CatalogRegistry(
    private val registryName: String,
    private val catalogUrl: String,
    private val catalogRef: String,
    private val cacheDir: File,
) : Registry {
    private val catalogDir = File(cacheDir, "_catalog")

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val found =
            findAny(packageName)
                ?: throw IllegalStateException(
                    "No package named \"$packageName\" found in registry \"$registryName\" catalog ($catalogUrl)",
                )

        val version = SemVer.parse(found.version)
        if (!CaretRange.satisfies(versionSpec, version)) {
            throw IllegalStateException(
                "Found \"$packageName\" in registry \"$registryName\", but its version ${found.version} " +
                    "does not satisfy $versionSpec",
            )
        }

        return found
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        ensureCatalogCloned()

        val referenceFile = File(catalogDir, "packages/$packageName.json")
        if (!referenceFile.exists()) return null

        val reference = readReference(referenceFile, packageName)
        val packageDir = File(cacheDir, packageName)
        ensurePackageCloned(reference, packageDir, packageName)

        val manifestFile = File(packageDir, "openedge-project.json")
        require(manifestFile.exists()) {
            "\"$packageName\" was fetched from ${reference.repoUrl} (ref ${reference.ref}) into " +
                "${packageDir.path}, but it has no openedge-project.json"
        }
        val manifest = ManifestReader.read(manifestFile)

        val packageRoot =
            manifest.sourceRoots.firstOrNull()
                ?: throw IllegalStateException(
                    "\"$packageName\" at ${packageDir.path} has no buildPath source entry to serve as its package_root",
                )

        return ResolvedPackage(
            packageName = packageName,
            version = manifest.version,
            sourceDir = File(packageDir, packageRoot),
            projectDir = packageDir,
        )
    }

    private fun ensureCatalogCloned() {
        if (File(catalogDir, ".git").exists()) {
            GitCli.run(catalogDir, "fetch", "origin", catalogRef)
            GitCli.run(catalogDir, "reset", "--hard", "FETCH_HEAD")
            return
        }

        cacheDir.mkdirs()
        GitCli.run(null, "clone", "--branch", catalogRef, catalogUrl, catalogDir.path)
    }

    private fun ensurePackageCloned(reference: PackageReference, packageDir: File, packageName: String) {
        if (File(packageDir, ".git").exists()) return

        cacheDir.mkdirs()
        try {
            GitCli.run(null, "clone", "--depth", "1", "--branch", reference.ref, reference.repoUrl, packageDir.path)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to fetch \"$packageName\" from ${reference.repoUrl} at ref \"${reference.ref}\" " +
                    "(registry \"$registryName\"): ${e.message}",
                e,
            )
        }
    }

    private data class PackageReference(val repoUrl: String, val ref: String)

    private fun readReference(file: File, packageName: String): PackageReference {
        val json =
            try {
                JSONObject(file.readText())
            } catch (e: JSONException) {
                throw IllegalStateException("Malformed catalog reference file for \"$packageName\": ${file.path}", e)
            }

        val repoUrl =
            json.optString("repoUrl").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"repoUrl\"")
        val ref =
            json.optString("ref").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"ref\"")

        return PackageReference(repoUrl, ref)
    }
}
