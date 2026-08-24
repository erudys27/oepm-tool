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
 * example so far has. Removing entries for dependencies that are no
 * longer declared is not implemented — see docs/spec/propath-generation.md.
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
}
