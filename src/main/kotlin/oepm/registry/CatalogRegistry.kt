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
 * dedicated repo URL + tag. Fetching a package goes through
 * GitPackageFetcher's bare-clone-plus-worktree cache, no partial-clone
 * machinery involved: the catalog is small enough to clone in full, and
 * each package repo is small and dedicated to one package.
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
 * Multi-version support (decided 2026-09-04): a package's catalog folder
 * can hold any number of version files. resolve(versionSpec) picks the
 * highest version whose catalog-declared "version" field satisfies the
 * caret range; findAny picks the highest version available, unfiltered.
 * Selection reads each candidate's cheap, local catalog metadata only -
 * it never fetches a candidate just to compare versions, only the one
 * actually picked. The catalog's declared "version" is used purely to
 * choose *which* reference to fetch; the ResolvedPackage's own version
 * still comes from that package's own fetched openedge-project.json,
 * exactly as before - the catalog's claim and the real repo's own
 * declared version are expected to agree, but nothing here enforces that
 * beyond what IntegrityChecker already catches for tag-hijack scenarios.
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
        val localName = localNameOf(packageName)
        ensureCatalogCloned()

        val references = findAllReferences(localName)
        require(references.isNotEmpty()) {
            "No package named \"$packageName\" found in registry \"$registryName\" catalog ($catalogUrl)"
        }

        val best =
            references
                .filter { CaretRange.satisfies(versionSpec, SemVer.parse(it.version)) }
                .maxByOrNull { SemVer.parse(it.version) }
                ?: throw IllegalStateException(
                    "Found \"$packageName\" in registry \"$registryName\", but none of its available " +
                        "versions (${references.joinToString(", ") { it.version }}) satisfy $versionSpec",
                )

        return fetchAndBuild(packageName, localName, best)
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        val localName = localNameOf(packageName)
        ensureCatalogCloned()

        val references = findAllReferences(localName)
        val best = references.maxByOrNull { SemVer.parse(it.version) } ?: return null

        return fetchAndBuild(packageName, localName, best)
    }

    private fun localNameOf(packageName: String): String {
        require(packageName.startsWith(prefix)) {
            "\"$packageName\" doesn't start with registry \"$registryName\"'s configured prefix \"$prefix\" " +
                "— this registry should only ever be asked about names PrefixRoutingRegistry already routed to it"
        }
        return packageName.removePrefix(prefix)
    }

    private fun fetchAndBuild(packageName: String, localName: String, reference: PackageReference): ResolvedPackage {
        val packageDir = File(cacheDir, localName)
        val fetched = GitPackageFetcher.fetch(packageName, reference.repoUrl, reference.ref, packageDir)
        val installSubpath = prefix.trimEnd('.').takeIf { it.isNotEmpty() }?.let { "$it/$localName" }
        return fetched.copy(installSubpath = installSubpath)
    }

    /** Every version reference file under a package's catalog folder, parsed. Empty if the package isn't in this catalog at all. */
    private fun findAllReferences(localName: String): List<PackageReference> {
        val packageDir = File(catalogDir, "packages/$localName")
        if (!packageDir.isDirectory) return emptyList()

        val versionFiles = packageDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        return versionFiles.map { readReference(it, localName) }
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

    private data class PackageReference(val repoUrl: String, val version: String, val ref: String)

    private fun readReference(file: File, localName: String): PackageReference {
        val json =
            try {
                JSONObject(file.readText())
            } catch (e: JSONException) {
                throw IllegalStateException("Malformed catalog reference file for \"$localName\": ${file.path}", e)
            }

        val repoUrl =
            json.optString("repoUrl").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"repoUrl\"")
        val version =
            json.optString("version").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"version\"")
        val ref =
            json.optString("ref").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Catalog reference file ${file.path} is missing \"ref\"")

        return PackageReference(repoUrl, version, ref)
    }
}
