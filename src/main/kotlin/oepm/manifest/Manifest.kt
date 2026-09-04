package oepm.manifest

/**
 * A single dependencies-map entry. Two shapes (see docs/spec/manifest-schema.md):
 * - Registry: today's shape, a plain caret-range string ("^1.0.0") - the
 *   name must already be a fully-qualified, registry-routed identity
 *   (e.g. "ba.greeter").
 * - DirectSource: an inline {repoUrl, ref} object - fetched by a plain git
 *   clone, no registry lookup at all. Always keyed by its own bare
 *   declared name, root-level or transitive - no inherited prefix (tried,
 *   then dropped - see oepm.resolver.DependencyResolver's class doc for
 *   why).
 */
sealed interface DependencySpec {
    data class Registry(val versionSpec: String) : DependencySpec

    data class DirectSource(val repoUrl: String, val ref: String) : DependencySpec
}

data class Manifest(
    val name: String,
    val version: String,
    val packageName: String,
    val dependencies: Map<String, DependencySpec> = emptyMap(),
    // buildPath entries of type "source", in declared order. sourceRoots.first()
    // also serves as package_root (derived, not a separate field — see
    // docs/spec/manifest-schema.md).
    val sourceRoots: List<String> = emptyList(),
    // buildPath entries of type "test", in declared order. Only relevant
    // to this project's own PROPATH (PropathGenerator's includeTests
    // option) - never read by GitPackageFetcher/CatalogRegistry when this
    // manifest belongs to a *dependency*, so a dependency's own test
    // folders are never copied into a consumer's oepm_packages/ or
    // exposed on a consumer's PROPATH.
    val testRoots: List<String> = emptyList(),
)
