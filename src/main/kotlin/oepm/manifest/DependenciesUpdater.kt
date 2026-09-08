package oepm.manifest

import org.json.JSONObject
import java.io.File

/** Adds/updates one entry in openedge-project.json's dependencies - what -PoepmAdd uses instead of a manual edit. */
object DependenciesUpdater {
    fun addDependency(manifestFile: File, packageName: String, versionSpec: String) {
        val json = JSONObject(manifestFile.readText())
        val dependencies = json.optJSONObject("dependencies") ?: JSONObject()
        dependencies.put(packageName, versionSpec)
        json.put("dependencies", dependencies)
        ManifestWriter.write(manifestFile, json)
    }
}
