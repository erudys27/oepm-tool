package oepm.manifest

data class Manifest(
    val name: String,
    val version: String,
    val packageName: String,
    val dependencies: Map<String, String> = emptyMap(),
    // buildPath entries of type "source", in declared order. sourceRoots.first()
    // also serves as package_root (derived, not a separate field — see
    // docs/spec/manifest-schema.md).
    val sourceRoots: List<String> = emptyList(),
)
