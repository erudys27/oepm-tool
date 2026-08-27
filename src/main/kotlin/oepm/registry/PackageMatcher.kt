package oepm.registry

import oepm.manifest.Manifest

/**
 * Shared "find the one candidate whose manifest declares this package_name,
 * fail loudly on more than one" logic used by a Registry implementation
 * that scans a set of candidates rather than looking one up directly by
 * name (e.g. LocalDirectoryRegistry's folders). CatalogRegistry doesn't
 * use this - it looks a package up directly by catalog path, so there's
 * never a set of candidates to disambiguate.
 */
object PackageMatcher {
    fun <T> selectUnique(
        candidates: List<Pair<T, Manifest>>,
        packageName: String,
        describeLocation: (T) -> String,
    ): Pair<T, Manifest>? {
        val matches = candidates.filter { (_, manifest) -> manifest.packageName == packageName }

        if (matches.size > 1) {
            val locations = matches.joinToString(", ") { (location, _) -> describeLocation(location) }
            throw IllegalStateException(
                "Multiple packages named \"$packageName\" found: $locations. " +
                    "package_name must be unique across the registry.",
            )
        }

        return matches.singleOrNull()
    }
}
