package oepm.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringWriter

/**
 * Writes openedge-project.json back to disk in a fixed, readable key
 * order, instead of whatever order org.json's JSONObject happens to
 * produce (its JSONObject is backed by a plain HashMap, not a
 * LinkedHashMap - re-`put`-ing keys into a fresh JSONObject in the
 * desired order does NOT make it serialize in that order, since
 * JSONObject#toString iterates its own internal hash-bucket order
 * regardless of insertion order).
 *
 * Every oepm write path - ManifestReader's package_name autofill,
 * DependenciesUpdater, BuildPathUpdater - goes through this, so a
 * manifest's key order stays predictable no matter which one last touched
 * the file. Purely cosmetic - nothing in oepm reads key order - but keeps
 * a fixed, deliberate order (name, version, oeversion, package_name,
 * dependencies, buildPath) rather than wherever org.json's internal
 * ordering happens to put them.
 */
object ManifestWriter {
    private val canonicalKeyOrder = listOf("name", "version", "oeversion", "package_name", "dependencies", "buildPath")
    private const val indentFactor = 2

    fun write(file: File, json: JSONObject) {
        val orderedKeys = canonicalKeyOrder.filter { json.has(it) } + json.keySet().filter { it !in canonicalKeyOrder }

        val writer = StringWriter()
        writer.write("{\n")
        orderedKeys.forEachIndexed { index, key ->
            writer.write(" ".repeat(indentFactor))
            writer.write(JSONObject.quote(key))
            writer.write(": ")
            writeValue(writer, json.get(key))
            writer.write(if (index != orderedKeys.lastIndex) ",\n" else "\n")
        }
        writer.write("}\n")

        file.writeText(writer.toString())
    }

    // JSONObject/JSONArray's own write(Writer, indentFactor, indent) is what
    // toString(indentFactor) delegates to internally - calling it directly,
    // starting at indent = indentFactor (one level in), lets a nested
    // object/array continue at the right depth instead of restarting at 0.
    private fun writeValue(writer: StringWriter, value: Any) {
        when (value) {
            is JSONObject -> value.write(writer, indentFactor, indentFactor)
            is JSONArray -> value.write(writer, indentFactor, indentFactor)
            else -> writer.write(JSONObject.valueToString(value))
        }
    }
}
