package oepm.manifest

import org.json.JSONObject
import java.io.File

/**
 * Adds (or updates) a single entry in openedge-project.json's dependencies
 * map. Used by oepmInstall's -PoepmAdd=<package_name>[:<versionSpec>]
 * one-shot "add and resolve" mode, so a dependency can be declared and
 * resolved in a single command without hand-editing the manifest first.
 */
object DependenciesUpdater {
    fun addDependency(manifestFile: File, packageName: String, versionSpec: String) {
        val json = JSONObject(manifestFile.readText())
        val dependencies = json.optJSONObject("dependencies") ?: JSONObject()
        dependencies.put(packageName, versionSpec)
        json.put("dependencies", dependencies)
        ManifestWriter.write(manifestFile, json)
    }
}
