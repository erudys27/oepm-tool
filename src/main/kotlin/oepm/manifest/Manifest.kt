package oepm.manifest

/**
 * A single dependencies-map entry. Two shapes (see docs/spec/manifest-schema.md):
 * - Registry: today's shape, a plain caret-range string ("^1.0.0") - the
 *   name must already be a fully-qualified, registry-routed identity
 *   (e.g. "ba.greeter").
 * - DirectSource: an inline {repoUrl, ref} object - fetched by a plain git
 *   clone, no registry lookup at all. Its declared name doesn't need to be
 *   registry-qualified; when it's a *transitive* dependency (declared
 *   inside another package's own manifest), oepm.resolver.DependencyResolver
 *   inherits the enclosing package's own registry prefix onto it.
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
)
