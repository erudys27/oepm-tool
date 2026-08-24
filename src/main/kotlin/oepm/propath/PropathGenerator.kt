package oepm.propath

import oepm.manifest.Manifest
import java.io.File

/**
 * v1: PROPATH is the project's own declared buildPath source roots,
 * resolved to absolute paths, in declared order — per
 * docs/spec/propath-generation.md's decided v1 default (own source
 * first, dependencies after). buildPath is expected to already include
 * any oepm_packages/<dep>/src entries from a prior oepmInstall.
 *
 * Auto-appending newly resolved dependencies that aren't yet reflected
 * in buildPath is not implemented — see the shadow-warning open question
 * in propath-generation.md.
 */
object PropathGenerator {
    fun generate(projectDir: File, manifest: Manifest): List<String> =
        manifest.sourceRoots.map { path -> File(projectDir, path).absolutePath }
}
