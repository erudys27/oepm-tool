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
 * `includeTests` additionally appends the project's own buildPath
 * entries of type "test", after the source roots — for running the
 * project's own tests locally, not for a "production"/consumer-facing
 * PROPATH (default false). This only ever affects *this* manifest's own
 * test roots: a dependency's "test" entries are never read at all when
 * fetching that dependency (see oepm.manifest.Manifest's testRoots doc),
 * so they can't leak onto a consumer's PROPATH regardless of this flag.
 *
 * Auto-appending newly resolved dependencies that aren't yet reflected
 * in buildPath is not implemented — see the shadow-warning open question
 * in propath-generation.md.
 */
object PropathGenerator {
    fun generate(projectDir: File, manifest: Manifest, includeTests: Boolean = false): List<String> {
        val roots = if (includeTests) manifest.sourceRoots + manifest.testRoots else manifest.sourceRoots
        return roots.map { path -> File(projectDir, path).absolutePath }
    }
}
