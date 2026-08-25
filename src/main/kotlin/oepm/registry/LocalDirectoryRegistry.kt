package oepm.registry

import oepm.manifest.ManifestReader
import oepm.version.CaretRange
import oepm.version.SemVer
import java.io.File

/**
 * v1 registry layout: a root directory containing one subfolder per
 * package. Each subfolder is that package's own project root, with its
 * own openedge-project.json declaring package_name + version. Only one
 * version per package is supported — v1 has no multi-version registry
 * support (matches every example built so far; a real future registry
 * with multiple versions per package is out of scope here).
 */
class LocalDirectoryRegistry(private val root: File) : Registry {
    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val found =
            findAny(packageName)
                ?: throw IllegalStateException("No package named \"$packageName\" found under registry root ${root.path}")

        val version = SemVer.parse(found.version)
        if (!CaretRange.satisfies(versionSpec, version)) {
            throw IllegalStateException(
                "Found \"$packageName\" in registry, but its version ${found.version} does not satisfy $versionSpec",
            )
        }

        return found
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        require(root.isDirectory) { "Registry root not found or not a directory: ${root.path}" }

        val candidates =
            root.listFiles { file -> file.isDirectory }.orEmpty()
                .mapNotNull { candidateDir ->
                    val manifestFile = File(candidateDir, "openedge-project.json")
                    if (!manifestFile.exists()) return@mapNotNull null
                    candidateDir to ManifestReader.read(manifestFile)
                }.sortedBy { (candidateDir, _) -> candidateDir.name }

        val (candidateDir, manifest) =
            PackageMatcher.selectUnique(candidates, packageName, describeLocation = { it.path }) ?: return null

        val packageRoot =
            manifest.sourceRoots.firstOrNull()
                ?: throw IllegalStateException(
                    "\"$packageName\" at ${candidateDir.path} has no buildPath source entry to serve as its package_root",
                )

        return ResolvedPackage(
            packageName = packageName,
            version = manifest.version,
            sourceDir = File(candidateDir, packageRoot),
            projectDir = candidateDir,
        )
    }
}
