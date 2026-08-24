package oepm.manifest

import org.json.JSONObject
import java.io.File

/**
 * Reads oepm's manifest fields from openedge-project.json.
 * Per docs/spec/manifest-schema.md, there is no separate oepm.json —
 * oepm-owned keys (package_name, dependencies) live alongside the
 * vscode-abl extension's own existing keys (name, version, buildPath).
 */
object ManifestReader {
    fun read(file: File): Manifest {
        require(file.exists()) { "Manifest not found: ${file.path}" }
        val json = JSONObject(file.readText())

        val sourceRoots =
            json.optJSONArray("buildPath")?.let { entries ->
                (0 until entries.length()).mapNotNull { i ->
                    val entry = entries.getJSONObject(i)
                    entry.optString("path").takeIf { entry.optString("type") == "source" }
                }
            } ?: emptyList()

        val packageName =
            if (json.has("package_name")) {
                json.getString("package_name")
            } else {
                inferAndPersistPackageName(file, json, sourceRoots)
            }

        val dependencies =
            json.optJSONObject("dependencies")?.let { deps ->
                deps.keySet().associateWith { deps.getString(it) }
            } ?: emptyMap()

        return Manifest(
            name = json.getString("name"),
            version = json.getString("version"),
            packageName = packageName,
            dependencies = dependencies,
            sourceRoots = sourceRoots,
        )
    }

    /**
     * Autofill, not validation: only runs when package_name is absent
     * entirely. Infers it from the package's own .cls files
     * (PackageNameInferrer) and writes it into openedge-project.json on
     * disk, so this only ever happens once per package — every read after
     * that finds package_name already present and skips inference.
     */
    private fun inferAndPersistPackageName(file: File, json: JSONObject, sourceRoots: List<String>): String {
        val packageRoot =
            sourceRoots.firstOrNull()
                ?: throw IllegalArgumentException(
                    "Missing required \"package_name\" in ${file.path}, and it can't be inferred: " +
                        "no buildPath source entry to scan for .cls files — see docs/spec/manifest-schema.md",
                )

        val inferred = PackageNameInferrer.infer(File(file.parentFile, packageRoot))

        json.put("package_name", inferred)
        ManifestWriter.write(file, json)

        return inferred
    }
}
