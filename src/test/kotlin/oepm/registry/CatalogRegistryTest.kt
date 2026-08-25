package oepm.registry

import oepm.fetch.GitCli
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRegistryTest {
    private fun git(dir: File, vararg args: String) = GitCli.run(dir, *args)

    private fun initRepo(dir: File) {
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "oepm-test@example.com")
        git(dir, "config", "user.name", "oepm test")
    }

    private fun commitAll(dir: File, message: String) {
        git(dir, "add", "-A")
        git(dir, "commit", "-m", message)
    }

    /** A standalone package repo, tagged at its declared version. */
    private fun packageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        initRepo(dir)
        File(dir, "openedge-project.json").writeText(
            """
            {
              "name": "$folderName",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()
        File(dir, "src/marker.i").writeText("/* $packageName source marker */")
        commitAll(dir, "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    private fun catalogRepo(root: File, references: Map<String, Pair<File, String>>): File {
        val dir = File(root, "catalog")
        initRepo(dir)
        File(dir, "packages").mkdirs()
        for ((packageName, repoAndVersion) in references) {
            val (repoDir, version) = repoAndVersion
            File(dir, "packages/$packageName.json").writeText(
                """
                { "repoUrl": "${repoDir.absolutePath.replace("\\", "\\\\")}", "version": "$version", "ref": "v$version" }
                """.trimIndent(),
            )
        }
        commitAll(dir, "initial")
        return dir
    }

    @Test
    fun `resolves a package via the catalog and fetches only that package`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val greeterRepo = packageRepo(remotesRoot, "greeter-package", "example.greeter", "1.0.0")
        val catalog =
            catalogRepo(
                remotesRoot,
                mapOf("example.calculator" to (calculatorRepo to "1.0.0"), "example.greeter" to (greeterRepo to "1.0.0")),
            )

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = CatalogRegistry("test", catalog.absolutePath, "main", cacheDir)

        val resolved = registry.findAny("example.calculator")

        assertEquals("example.calculator", resolved?.packageName)
        assertEquals("1.0.0", resolved?.version)
        assertEquals("src", resolved?.sourceDir?.name)
        assertTrue(File(cacheDir, "example.calculator/.git").exists())
        assertFalse(File(cacheDir, "example.greeter").exists())
    }

    @Test
    fun `resolve checks the version range`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = CatalogRegistry("test", catalog.absolutePath, "main", cacheDir)

        assertEquals("1.0.0", registry.resolve("example.calculator", "^1.0.0").version)
        assertFailsWith<IllegalStateException> { registry.resolve("example.calculator", "^2.0.0") }
    }

    @Test
    fun `findAny returns null when the catalog has no entry for that package`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = CatalogRegistry("test", catalog.absolutePath, "main", cacheDir)

        assertNull(registry.findAny("example.other"))
    }

    @Test
    fun `a second resolve reuses the cached catalog and package clones without error`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = CatalogRegistry("test", catalog.absolutePath, "main", cacheDir)

        registry.findAny("example.calculator")
        val second = registry.findAny("example.calculator")

        assertEquals("1.0.0", second?.version)
    }

    @Test
    fun `a package is fetched as a shallow clone, not full history`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        // A second commit in the source repo so a full clone would carry >1 commit.
        File(calculatorRepo, "src/extra.i").writeText("/* extra */")
        commitAll(calculatorRepo, "second commit")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = CatalogRegistry("test", catalog.absolutePath, "main", cacheDir)

        registry.findAny("example.calculator")

        val packageDir = File(cacheDir, "example.calculator")
        val commitCount = git(packageDir, "rev-list", "--count", "HEAD").trim()
        assertEquals("1", commitCount)
    }
}
