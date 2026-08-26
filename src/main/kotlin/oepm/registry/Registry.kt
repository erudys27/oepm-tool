package oepm.registry

import java.io.File

data class ResolvedPackage(
    val packageName: String,
    val version: String,
    val sourceDir: File,
    // The package's own project root (where its openedge-project.json
    // lives) — distinct from sourceDir, which is that project's own
    // buildPath[0] source folder. Needed to read the package's own
    // dependencies for transitive resolution (see oepm.resolver.DependencyResolver).
    val projectDir: File,
    // The registry prefix that routed to this package (e.g. "ba."), null
    // when it wasn't routed at all (LocalDirectoryRegistry, or a
    // direct-source dependency). Stamped on by PrefixRoutingRegistry, not
    // known by individual Registry implementations themselves. Used by
    // DependencyResolver to inherit a namespace onto this package's own
    // direct-source dependencies — see oepm.manifest.DependencySpec.
    val resolvedPrefix: String? = null,
)

interface Registry {
    fun resolve(packageName: String, versionSpec: String): ResolvedPackage

    /** Finds a package by name only, ignoring version — used to auto-pick a
     * version when a caller doesn't specify one (e.g. `oepmInstall -PoepmAdd=<name>`). */
    fun findAny(packageName: String): ResolvedPackage?
}
