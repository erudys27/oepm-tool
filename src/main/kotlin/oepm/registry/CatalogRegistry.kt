package oepm.registry

import oepm.fetch.GitCli
import oepm.fetch.GitPackageFetcher
import oepm.version.CaretRange
import oepm.version.SemVer
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Registry backed by a small "catalog" git repo that holds no package
 * content itself — only one reference file per package version
 * (packages/<local_name>/<version>.json) pointing at that package's own
 * dedicated repo URL + tag. Fetching a package is a plain shallow clone of
 * that dedicated repo, no partial-clone machinery involved: the catalog is
 * small enough to clone in full, and each package repo is small and
 * dedicated to one package.
 *
 * "local_name" is packageName (e.g. "ba.calculator") with the registry's
 * own configured prefix stripped (e.g. "calculator"). The routing prefix
 * is purely a lookup key — it's not part of the package's own identity:
 * package_name inside a package's own manifest, and its actual OO ABL
 * namespace, stay whatever that package's own author chose ("calculator",
 * no "ba." baked in). Every package in one catalog shares the same
 * registry, so the prefix is redundant inside that catalog's own folder
 * names. The ResolvedPackage returned still carries the full, prefixed
 * packageName — that's the public identity used everywhere outside this
 * class (oepm.lock, oepm_packages/, dependency map keys).
 *
 * The folder-per-package layout is prep for future multi-version registry
 * support (see NEXT-STEPS.md) — v1 itself still only supports exactly one
 * version per package, same as LocalDirectoryRegistry: more than one
 * version file under a package's folder is a loud error, not a selection.
 *
 * Cache layout under cacheDir (one CatalogRegistry per configured
 * registry, so cacheDir is already scoped to this registry's name):
 *   _catalog/                 full clone of the catalog repo
 *   <local_name>/_bare.git/   bare clone of that package's own repo (see
 *                             GitPackageFetcher - no package files, just
 *                             the git history, shared across versions)
 *   <local_name>/<ref>/       worktree checkout of the version actually used
 */
class CatalogRegistry(
    private val registryName: String,
    private val prefix: String,
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
        require(packageName.startsWith(prefix)) {
            "\"$packageName\" doesn't start with registry \"$registryName\"'s configured prefix \"$prefix\" " +
                "— this registry should only ever be asked about names PrefixRoutingRegistry already routed to it"
        }
        val localName = packageName.removePrefix(prefix)

        ensureCatalogCloned()

        val referenceFile = findReferenceFile(localName, packageName) ?: return null
        val reference = readReference(referenceFile, packageName)
        val packageDir = File(cacheDir, localName)

        return GitPackageFetcher.fetch(packageName, reference.repoUrl, reference.ref, packageDir)
    }

    /**
     * v1 has no version selection: a package folder with more than one
     * version file is a loud error, not a pick. See class doc. Looks up by
     * localName (prefix stripped); error messages still name the full,
     * public packageName so a failure is recognizable from the outside.
     */
    private fun findReferenceFile(localName: String, packageName: String): File? {
        val packageDir = File(catalogDir, "packages/$localName")
        if (!packageDir.isDirectory) return null

        val versionFiles = packageDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        return when (versionFiles.size) {
            0 -> null
            1 -> versionFiles.single()
            else ->
                throw IllegalStateException(
                    "Registry \"$registryName\" catalog has multiple versions of \"$packageName\" " +
                        "(${versionFiles.joinToString(", ") { it.name }}), but v1 does not support " +
                        "selecting among multiple versions yet.",
                )
        }
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
