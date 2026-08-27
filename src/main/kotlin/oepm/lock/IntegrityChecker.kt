package oepm.lock

/**
 * Guards against a registry silently serving different content for a
 * version that's already locked (e.g. a git tag force-moved to a new
 * commit without bumping the version) - the supply-chain scenario
 * oepm.lock's integrity field exists to catch. Only compares when the
 * already-locked entry is for the *same* version: a genuine version bump
 * is expected to change content and isn't a mismatch.
 */
object IntegrityChecker {
    fun verify(packageName: String, version: String, freshIntegrity: String, existingLock: Map<String, LockedPackage>) {
        val existing = existingLock[packageName] ?: return
        if (existing.version != version) return

        require(existing.integrity == freshIntegrity) {
            "Integrity check failed for \"$packageName\" $version: expected ${existing.integrity} " +
                "(from oepm.lock) but the registry now resolves to $freshIntegrity. The content for this " +
                "already-locked version appears to have changed since it was last installed. If that's " +
                "expected, remove \"$packageName\" from oepm.lock and reinstall."
        }
    }
}
