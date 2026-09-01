package oepm.fetch

import oepm.manifest.ManifestReader
import oepm.registry.ResolvedPackage
import java.io.File

/**
 * Fetches one dedicated package repo at a given ref and builds a
 * ResolvedPackage from its own openedge-project.json. Shared by
 * CatalogRegistry (once it's found a repoUrl/ref via its catalog) and
 * oepm.resolver.DependencyResolver's direct-source dependency path (which
 * gets repoUrl/ref straight from a package's own manifest, no registry
 * involved at all) - the fetch mechanism is identical either way, only
 * *how* repoUrl/ref were found differs.
 *
 * Caching strategy: one bare clone per package (no working-tree files at
 * all - just the git object database and refs), plus one `git worktree`
 * checkout per ref actually requested. Fetching a version already present
 * in the bare repo's history is then a local, no-network `worktree add`;
 * only a ref that isn't in the cached history yet costs a `git fetch`, and
 * that fetch pulls incremental history rather than re-cloning from
 * scratch. Multiple versions of the same package share one object store
 * (git dedupes unchanged blobs between tags automatically), instead of
 * each version living in its own fully independent shallow clone.
 *
 * Layout under destDir (the "package folder", one per package):
 *   _bare.git/   bare clone - the actual cache, no package files in it
 *   <ref>/       a worktree checked out at that ref (what callers read)
 */
object GitPackageFetcher {
    fun fetch(packageName: String, repoUrl: String, ref: String, destDir: File): ResolvedPackage {
        val bareRepoDir = File(destDir, "_bare.git")
        val worktreeDir = File(destDir, sanitizeRefForPath(ref))

        try {
            ensureBareRepo(bareRepoDir, repoUrl)
            ensureWorktree(bareRepoDir, worktreeDir, ref)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to fetch \"$packageName\" from $repoUrl at ref \"$ref\": ${e.message}",
                e,
            )
        }

        val manifestFile = File(worktreeDir, "openedge-project.json")
        require(manifestFile.exists()) {
            "\"$packageName\" was fetched from $repoUrl (ref $ref) into ${worktreeDir.path}, " +
                "but it has no openedge-project.json"
        }
        val manifest = ManifestReader.read(manifestFile)

        val packageRoot =
            manifest.sourceRoots.firstOrNull()
                ?: throw IllegalStateException(
                    "\"$packageName\" at ${worktreeDir.path} has no buildPath source entry to serve as its package_root",
                )

        return ResolvedPackage(
            packageName = packageName,
            version = manifest.version,
            sourceDir = File(worktreeDir, packageRoot),
            projectDir = worktreeDir,
        )
    }

    private fun ensureBareRepo(bareRepoDir: File, repoUrl: String) {
        if (File(bareRepoDir, "HEAD").exists()) return

        bareRepoDir.parentFile?.mkdirs()
        GitCli.run(null, "clone", "--bare", repoUrl, bareRepoDir.path)
    }

    private fun ensureWorktree(bareRepoDir: File, worktreeDir: File, ref: String) {
        if (isWorktreeCheckedOutAt(bareRepoDir, worktreeDir, ref)) return

        if (!refExistsLocally(bareRepoDir, ref)) {
            GitCli.run(bareRepoDir, "fetch", "--tags", "--force", "origin")
        }

        if (worktreeDir.exists()) {
            // Stale worktree (wrong ref, or left behind by a previous
            // failed run) - drop it and re-add rather than trying to
            // reconcile in place.
            runCatching { GitCli.run(bareRepoDir, "worktree", "remove", "--force", worktreeDir.path) }
            worktreeDir.deleteRecursively()
            GitCli.run(bareRepoDir, "worktree", "prune")
        }

        GitCli.run(bareRepoDir, "worktree", "add", "--force", worktreeDir.path, ref)
    }

    private fun isWorktreeCheckedOutAt(bareRepoDir: File, worktreeDir: File, ref: String): Boolean {
        if (!File(worktreeDir, ".git").exists()) return false

        val headCommit = runCatching { GitCli.run(worktreeDir, "rev-parse", "HEAD").trim() }.getOrNull() ?: return false
        val refCommit = runCatching { GitCli.run(bareRepoDir, "rev-parse", "$ref^{commit}").trim() }.getOrNull() ?: return false
        return headCommit == refCommit
    }

    private fun refExistsLocally(bareRepoDir: File, ref: String): Boolean =
        runCatching { GitCli.run(bareRepoDir, "rev-parse", "--verify", "$ref^{commit}") }.isSuccess

    /** Refs are almost always plain tags (e.g. "v1.0.2"), but sanitize defensively. */
    private fun sanitizeRefForPath(ref: String): String = ref.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
