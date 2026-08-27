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
)

interface Registry {
    fun resolve(packageName: String, versionSpec: String): ResolvedPackage

    /** Finds a package by name only, ignoring version — used to auto-pick a
     * version when a caller doesn't specify one (e.g. `oepmInstall -PoepmAdd=<name>`). */
    fun findAny(packageName: String): ResolvedPackage?
}
