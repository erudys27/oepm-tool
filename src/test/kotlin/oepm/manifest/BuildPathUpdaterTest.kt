package oepm.manifest

import org.json.JSONObject
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildPathUpdaterTest {
    private fun manifestWithBuildPath(vararg paths: String): java.io.File {
        val file = createTempFile(suffix = ".json")
        val buildPath = paths.joinToString(",") { """{"type": "source", "path": "$it"}""" }
        file.writeText(
            """
            {
              "name": "consumer-app",
              "version": "1.0.0",
              "package_name": "example.consumer",
              "dependencies": {},
              "buildPath": [$buildPath]
            }
            """.trimIndent(),
        )
        return file.toFile()
    }

    private fun buildPathOf(file: java.io.File): List<String> {
        val array = JSONObject(file.readText()).getJSONArray("buildPath")
        return (0 until array.length()).map { array.getJSONObject(it).getString("path") }
    }

    @Test
    fun `appends a missing source entry after existing ones`() {
        val file = manifestWithBuildPath("src")

        BuildPathUpdater.ensureSourceEntries(file, listOf("oepm_packages/example.calculator/src"))

        assertEquals(listOf("src", "oepm_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `does not duplicate an entry that already exists`() {
        val file = manifestWithBuildPath("src", "oepm_packages/example.calculator/src")

        BuildPathUpdater.ensureSourceEntries(file, listOf("oepm_packages/example.calculator/src"))

        assertEquals(listOf("src", "oepm_packages/example.calculator/src"), buildPathOf(file))
    }

    @Test
    fun `adds multiple missing entries while preserving existing ones`() {
        val file = manifestWithBuildPath("src")

        BuildPathUpdater.ensureSourceEntries(
            file,
            listOf("oepm_packages/example.calculator/src", "oepm_packages/example.greeter/src"),
        )

        assertEquals(
            listOf("src", "oepm_packages/example.calculator/src", "oepm_packages/example.greeter/src"),
            buildPathOf(file),
        )
    }
}
