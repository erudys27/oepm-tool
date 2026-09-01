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
        for ((packageName, repoAndVersion) in references) {
            val (repoDir, version) = repoAndVersion
            File(dir, "packages/$packageName").mkdirs()
            File(dir, "packages/$packageName/$version.json").writeText(
                """
                { "repoUrl": "${repoDir.absolutePath.replace("\\", "\\\\")}", "version": "$version", "ref": "v$version" }
                """.trimIndent(),
            )
        }
        commitAll(dir, "initial")
        return dir
    }

    private fun registry(catalog: File, cacheDir: File, prefix: String = "") =
        CatalogRegistry("test", prefix, catalog.absolutePath, "main", cacheDir)

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
        val registry = registry(catalog, cacheDir)

        val resolved = registry.findAny("example.calculator")

        assertEquals("example.calculator", resolved?.packageName)
        assertEquals("1.0.0", resolved?.version)
        assertEquals("src", resolved?.sourceDir?.name)
        assertTrue(File(cacheDir, "example.calculator/_bare.git/HEAD").exists())
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())
        assertFalse(File(cacheDir, "example.greeter").exists())
    }

    @Test
    fun `resolve checks the version range`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertEquals("1.0.0", registry.resolve("example.calculator", "^1.0.0").version)
        assertFailsWith<IllegalStateException> { registry.resolve("example.calculator", "^2.0.0") }
    }

    @Test
    fun `findAny returns null when the catalog has no entry for that package`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        assertNull(registry.findAny("example.other"))
    }

    @Test
    fun `a second resolve reuses the cached catalog and package clones without error`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        registry.findAny("example.calculator")
        val second = registry.findAny("example.calculator")

        assertEquals("1.0.0", second?.version)
    }

    @Test
    fun `a package's worktree is checked out at exactly the resolved ref, even though the bare cache holds full history`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        // A commit after the tag, so the worktree (checked out at the tag)
        // must not see it even though the bare repo's history does.
        File(calculatorRepo, "src/extra.i").writeText("/* extra */")
        commitAll(calculatorRepo, "second commit")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        registry.findAny("example.calculator")

        val worktreeDir = File(cacheDir, "example.calculator/v1.0.0")
        val commitCount = git(worktreeDir, "rev-list", "--count", "HEAD").trim()
        assertEquals("1", commitCount)

        val bareRepoDir = File(cacheDir, "example.calculator/_bare.git")
        val bareCommitCount = git(bareRepoDir, "rev-list", "--count", "--all").trim()
        assertEquals("2", bareCommitCount)
    }

    @Test
    fun `a second, different version reuses the bare cache and only fetches locally`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepoV1 = packageRepo(remotesRoot, "calculator-package", "example.calculator", "1.0.0")
        File(calculatorRepoV1, "openedge-project.json").writeText(
            """
            {
              "name": "calculator-package-project",
              "version": "2.0.0",
              "package_name": "example.calculator",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(calculatorRepoV1, "src/marker.i").writeText("/* example.calculator source marker v2 */")
        commitAll(calculatorRepoV1, "bump to 2.0.0")
        git(calculatorRepoV1, "tag", "v2.0.0")

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepoV1 to "1.0.0")))

        registry(catalog, cacheDir).findAny("example.calculator")
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())

        // Re-point the same catalog repo at 2.0.0 and resolve again - same
        // bare package repo already has both tags, so this is a local
        // worktree add, not a re-clone; a second worktree lands alongside
        // the first rather than replacing it.
        File(catalog, "packages/example.calculator/1.0.0.json").delete()
        File(catalog, "packages/example.calculator/2.0.0.json").writeText(
            """
            { "repoUrl": "${calculatorRepoV1.absolutePath.replace("\\", "\\\\")}", "version": "2.0.0", "ref": "v2.0.0" }
            """.trimIndent(),
        )
        commitAll(catalog, "bump to 2.0.0")

        val resolved = registry(catalog, cacheDir).findAny("example.calculator")

        assertEquals("2.0.0", resolved?.version)
        assertTrue(File(cacheDir, "example.calculator/v2.0.0/.git").exists())
        assertTrue(File(cacheDir, "example.calculator/v1.0.0/.git").exists())
    }

    @Test
    fun `throws when a package's catalog folder has more than one version file`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepoV1 = packageRepo(remotesRoot, "calculator-package-v1", "example.calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("example.calculator" to (calculatorRepoV1 to "1.0.0")))
        // Simulate a second version file landing in the same package folder
        // (not yet reachable via catalogRepo() since v1 only ever writes one).
        File(catalog, "packages/example.calculator/2.0.0.json").writeText(
            """{ "repoUrl": "${calculatorRepoV1.absolutePath.replace("\\", "\\\\")}", "version": "2.0.0", "ref": "v1.0.0" }""",
        )
        git(catalog, "add", "-A")
        git(catalog, "commit", "-m", "add a second version file")

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir)

        val exception = assertFailsWith<IllegalStateException> { registry.findAny("example.calculator") }

        assertTrue(exception.message!!.contains("example.calculator"))
        assertTrue(exception.message!!.contains("1.0.0.json"))
        assertTrue(exception.message!!.contains("2.0.0.json"))
    }

    @Test
    fun `strips the configured prefix for catalog and cache paths, but keeps the full name as the public identity`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        // The package's own manifest/namespace has no "ba." prefix at all -
        // the prefix is purely a routing key, not part of the package's own identity.
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        val resolved = registry.findAny("ba.calculator")

        assertEquals("ba.calculator", resolved?.packageName)
        assertTrue(File(cacheDir, "calculator/_bare.git/HEAD").exists())
        assertTrue(File(cacheDir, "calculator/v1.0.0/.git").exists())
        assertFalse(File(cacheDir, "ba.calculator").exists())
    }

    @Test
    fun `rejects a package name that doesn't start with the configured prefix`() {
        val remotesRoot = createTempDirectory("oepm-catalog-remotes").toFile()
        val calculatorRepo = packageRepo(remotesRoot, "calculator-package", "calculator", "1.0.0")
        val catalog = catalogRepo(remotesRoot, mapOf("calculator" to (calculatorRepo to "1.0.0")))

        val cacheDir = createTempDirectory("oepm-catalog-cache").toFile()
        val registry = registry(catalog, cacheDir, prefix = "ba.")

        assertFailsWith<IllegalArgumentException> { registry.findAny("cw.calculator") }
    }
}
