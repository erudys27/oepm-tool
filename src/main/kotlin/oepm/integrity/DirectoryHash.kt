package oepm.integrity

import java.io.File
import java.security.MessageDigest

/**
 * Content hash of a resolved package's source tree, recorded as
 * oepm.lock's "integrity" field so a locked package can later be verified
 * against what was actually installed (see docs/spec/lockfile-format.md).
 * Deliberately not tied to any one registry's fetch mechanism (git commit,
 * local file copy, ...) - it hashes the files on disk after install, so it
 * works the same way for LocalDirectoryRegistry, CatalogRegistry, or
 * anything else that produces a ResolvedPackage.
 *
 * Modeled on Go's dirhash Hash1: hash every file, build a manifest of
 * "<file sha256>  <relative path>" lines sorted by path (so directory
 * walk order and OS path separators never affect the result), then hash
 * that manifest. Sorting + a manifest-of-hashes (rather than concatenating
 * raw file bytes) keeps the result independent of enumeration order and
 * sensitive to renames, not just content changes.
 */
object DirectoryHash {
    fun hash(dir: File): String {
        require(dir.isDirectory) { "Not a directory: ${dir.path}" }

        val manifest = StringBuilder()
        dir
            .walkTopDown()
            .filter { it.isFile }
            .map { file -> file.relativeTo(dir).invariantSeparatorsPath to file }
            .sortedBy { (relativePath, _) -> relativePath }
            .forEach { (relativePath, file) ->
                manifest.append(sha256Hex(file.readBytes())).append("  ").append(relativePath).append('\n')
            }

        return "sha256:" + sha256Hex(manifest.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
