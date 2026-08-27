package oepm.fetch

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitPackageFetcherTest {
    private fun git(dir: File, vararg args: String) = GitCli.run(dir, *args)

    private fun packageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "oepm-test@example.com")
        git(dir, "config", "user.name", "oepm test")
        File(dir, "openedge-project.json").writeText(
            """
            {
              "name": "$folderName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": {},
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(dir, "src").mkdirs()
        File(dir, "src/marker.i").writeText("/* $packageName source marker */")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    @Test
    fun `clones the repo at the given ref and builds a ResolvedPackage from its manifest`() {
        val remotesRoot = createTempDirectory("oepm-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("oepm-fetcher-dest").toFile(), "greeter")

        val resolved = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("ba.greeter", resolved.packageName)
        assertEquals("1.0.1", resolved.version)
        assertEquals("src", resolved.sourceDir.name)
        assertEquals(destDir, resolved.projectDir)
    }

    @Test
    fun `reuses an already-cloned destination instead of cloning again`() {
        val remotesRoot = createTempDirectory("oepm-fetcher-remotes").toFile()
        val repo = packageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val destDir = File(createTempDirectory("oepm-fetcher-dest").toFile(), "greeter")

        GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)
        val second = GitPackageFetcher.fetch("ba.greeter", repo.absolutePath, "v1.0.1", destDir)

        assertEquals("1.0.1", second.version)
    }

    @Test
    fun `throws a clear error when the cloned repo has no openedge-project json`() {
        val remotesRoot = createTempDirectory("oepm-fetcher-remotes").toFile()
        val dir = File(remotesRoot, "empty-repo").apply { mkdirs() }
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "oepm-test@example.com")
        git(dir, "config", "user.name", "oepm test")
        File(dir, "README.md").writeText("nothing here")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v1.0.0")
        val destDir = File(createTempDirectory("oepm-fetcher-dest").toFile(), "empty")

        assertFailsWith<IllegalArgumentException> {
            GitPackageFetcher.fetch("ba.empty", dir.absolutePath, "v1.0.0", destDir)
        }
    }
}
