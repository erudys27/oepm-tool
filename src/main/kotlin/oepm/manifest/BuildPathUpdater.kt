package oepm.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ensures resolved dependencies' oepm_packages source paths are present in
 * openedge-project.json's buildPath, so oepmPropath picks them up without
 * a manual edit.
 *
 * Additive only: existing entries (the project's own source, or anything
 * else already there) are preserved and never removed or reordered — new
 * entries are appended after everything already present, matching the v1
 * PROPATH-ordering default (own source first, dependencies after) as long
 * as the project's own source root was already listed first, which every
 * example so far has. Removing stale entries (a dependency that's no
 * longer part of the resolved graph) is a separate, explicit step -
 * pruneStaleOepmPackagesEntries below, used by the oepmPrune task - not
 * something ensureSourceEntries/oepmInstall ever does implicitly.
 */
object BuildPathUpdater {
    fun ensureSourceEntries(manifestFile: File, paths: List<String>) {
        val json = JSONObject(manifestFile.readText())
        val buildPath = json.optJSONArray("buildPath") ?: JSONArray()

        val existingPaths =
            (0 until buildPath.length())
                .map { buildPath.getJSONObject(it) }
                .filter { it.optString("type") == "source" }
                .map { it.getString("path") }
                .toSet()

        var changed = false
        for (path in paths) {
            if (path !in existingPaths) {
                buildPath.put(JSONObject().put("type", "source").put("path", path))
                changed = true
            }
        }

        if (changed) {
            json.put("buildPath", buildPath)
            ManifestWriter.write(manifestFile, json)
        }
    }

    /**
     * The oepm prune counterpart to ensureSourceEntries above: removes
     * buildPath "source" entries that look like ones oepm itself would
     * have generated (path starts with "oepm_packages/") but aren't in
     * expectedPaths — i.e. a dependency that's no longer part of the
     * resolved graph. Deliberately narrow: only ever touches
     * "oepm_packages/..." entries, never the project's own source or
     * anything else already in buildPath, matching ensureSourceEntries'
     * own "never remove what we didn't add" rule.
     *
     * dryRun: computes and returns what *would* be removed without
     * writing anything to disk - lets a caller preview before committing.
     *
     * Returns the removed (or would-be-removed) paths, in declared order.
     */
    fun pruneStaleOepmPackagesEntries(manifestFile: File, expectedPaths: Set<String>, dryRun: Boolean = false): List<String> {
        val json = JSONObject(manifestFile.readText())
        val buildPath = json.optJSONArray("buildPath") ?: JSONArray()

        val entries = (0 until buildPath.length()).map { buildPath.getJSONObject(it) }
        val stale =
            entries.filter { entry ->
                entry.optString("type") == "source" &&
                    entry.optString("path").startsWith("oepm_packages/") &&
                    entry.optString("path") !in expectedPaths
            }
        if (stale.isEmpty()) return emptyList()

        val removedPaths = stale.map { it.getString("path") }

        if (!dryRun) {
            val kept = JSONArray()
            entries.filter { it !in stale }.forEach { kept.put(it) }
            json.put("buildPath", kept)
            ManifestWriter.write(manifestFile, json)
        }

        return removedPaths
    }
}
