package oepm.resolver

import oepm.registry.LocalDirectoryRegistry
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DependencyResolverTest {
    private fun registryRoot(): File = createTempDirectory("oepm-resolver-test").toFile()

    private fun addPackage(root: File, packageName: String, version: String, dependencies: Map<String, String> = emptyMap()) {
        val packageDir = File(root, packageName).apply { mkdirs() }
        val depsJson = dependencies.entries.joinToString(",") { (name, spec) -> "\"$name\": \"$spec\"" }
        File(packageDir, "openedge-project.json").writeText(
            """
            {
              "name": "$packageName-project",
              "version": "$version",
              "package_name": "$packageName",
              "dependencies": { $depsJson },
              "buildPath": [{ "type": "source", "path": "src" }]
            }
            """.trimIndent(),
        )
        File(packageDir, "src").mkdirs()
    }

    @Test
    fun `resolves a transitive dependency of a direct dependency`() {
        val root = registryRoot()
        addPackage(root, "example.calculator", "1.0.0", dependencies = mapOf("example.greeter" to "^1.0.0"))
        addPackage(root, "example.greeter", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved = DependencyResolver.resolveAll(mapOf("example.calculator" to "^1.0.0"), registry)

        assertEquals(setOf("example.calculator", "example.greeter"), resolved.keys)
        assertEquals("1.0.0", resolved.getValue("example.greeter").version)
    }

    @Test
    fun `a diamond dependency with a compatible version resolves once`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.shared", "1.2.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved =
            DependencyResolver.resolveAll(
                mapOf("example.a" to "^1.0.0", "example.b" to "^1.0.0"),
                registry,
            )

        assertEquals(setOf("example.a", "example.b", "example.shared"), resolved.keys)
        assertEquals("1.2.0", resolved.getValue("example.shared").version)
    }

    @Test
    fun `a diamond dependency with an incompatible version requirement fails loudly`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.shared" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.shared" to "^2.0.0"))
        addPackage(root, "example.shared", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(
                    mapOf("example.a" to "^1.0.0", "example.b" to "^1.0.0"),
                    registry,
                )
            }
        assertTrue(error.message!!.contains("Version conflict"), "Expected a version-conflict error, got: ${error.message}")
    }

    @Test
    fun `a circular dependency fails loudly instead of infinite-looping`() {
        val root = registryRoot()
        addPackage(root, "example.a", "1.0.0", dependencies = mapOf("example.b" to "^1.0.0"))
        addPackage(root, "example.b", "1.0.0", dependencies = mapOf("example.a" to "^1.0.0"))
        val registry = LocalDirectoryRegistry(root)

        val error =
            assertFailsWith<IllegalStateException> {
                DependencyResolver.resolveAll(mapOf("example.a" to "^1.0.0"), registry)
            }
        assertTrue(error.message!!.contains("Circular dependency"), "Expected a circular-dependency error, got: ${error.message}")
    }

    @Test
    fun `a package with no dependencies key resolves fine on its own`() {
        val root = registryRoot()
        addPackage(root, "example.calculator", "1.0.0")
        val registry = LocalDirectoryRegistry(root)

        val resolved = DependencyResolver.resolveAll(mapOf("example.calculator" to "^1.0.0"), registry)

        assertEquals(setOf("example.calculator"), resolved.keys)
    }
}
