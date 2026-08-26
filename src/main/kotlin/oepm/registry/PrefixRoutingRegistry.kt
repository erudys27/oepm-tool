package oepm.registry

/**
 * Routes a package_name to exactly one configured registry by longest
 * matching prefix (e.g. "ba." -> the BA registry) — "option A" from the
 * registry-routing decision. No probing multiple registries in order: a
 * package_name with no matching configured prefix is a loud error (a
 * config problem, or a genuinely missing/mistyped package), not a silent
 * fallback.
 */
class PrefixRoutingRegistry(private val delegatesByPrefix: Map<String, Registry>) : Registry {
    private fun route(packageName: String): Pair<String, Registry> =
        delegatesByPrefix.entries
            .filter { (prefix, _) -> packageName.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.toPair()
            ?: throw IllegalStateException(
                "No configured registry prefix matches \"$packageName\" " +
                    "(configured prefixes: ${delegatesByPrefix.keys.joinToString(", ")})",
            )

    override fun resolve(packageName: String, versionSpec: String): ResolvedPackage {
        val (prefix, delegate) = route(packageName)
        return delegate.resolve(packageName, versionSpec).copy(resolvedPrefix = prefix)
    }

    override fun findAny(packageName: String): ResolvedPackage? {
        val (prefix, delegate) = route(packageName)
        return delegate.findAny(packageName)?.copy(resolvedPrefix = prefix)
    }
}
