package oepm.manifest

import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependenciesUpdaterTest {
    private fun manifestWithDependencies(dependenciesJson: String): File {
        val file = createTempFile(suffix = ".json")
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "dependencies": $dependenciesJson,
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        return file.toFile()
    }

    @Test
    fun `adds a new dependency to an empty map`() {
        val file = manifestWithDependencies("{}")

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("dependencies")
        assertEquals("^1.0.0", dependencies.getString("example.calculator"))
    }

    @Test
    fun `adds a dependency alongside existing ones without touching them`() {
        val file = manifestWithDependencies("""{"example.greeter": "^2.0.0"}""")

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("dependencies")
        assertEquals("^1.0.0", dependencies.getString("example.calculator"))
        assertEquals("^2.0.0", dependencies.getString("example.greeter"))
    }

    @Test
    fun `overwrites the version spec when the dependency already exists`() {
        val file = manifestWithDependencies("""{"example.calculator": "^1.0.0"}""")

        DependenciesUpdater.addDependency(file, "example.calculator", "^2.0.0")

        val dependencies = JSONObject(file.readText()).getJSONObject("dependencies")
        assertEquals("^2.0.0", dependencies.getString("example.calculator"))
    }

    @Test
    fun `adding a dependency that didn't exist yet places the key right after version`() {
        val file = createTempFile(suffix = ".json").toFile()
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )

        DependenciesUpdater.addDependency(file, "example.calculator", "^1.0.0")

        // org.json's JSONObject is backed by a plain HashMap, so re-parsing
        // and reading keySet() back would lose order again - only the raw
        // text can confirm where the newly-added key actually landed.
        val text = file.readText()
        assertTrue(
            text.indexOf("\"version\"") < text.indexOf("\"dependencies\"") &&
                text.indexOf("\"dependencies\"") < text.indexOf("\"buildPath\""),
            "Expected \"dependencies\" right after \"version\", got:\n$text",
        )
    }
}
