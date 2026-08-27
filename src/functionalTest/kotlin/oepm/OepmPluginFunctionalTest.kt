package oepm

import org.gradle.testkit.runner.GradleRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the real plugin, via TestKit, against a throwaway copy of this
 * repo's own small fixture packages (src/functionalTest/resources/fixtures/) —
 * self-contained, not dependent on any other repo existing (the demo app
 * and registry content now live in separate repos, e.g.
 * github.com/erudys27/openedge-package-manager).
 */
class OepmPluginFunctionalTest {
    /**
     * Builds a throwaway registry containing copies of this repo's own
     * calculator-package and greeter-package fixtures. Both are needed
     * together now: calculator-package genuinely depends on
     * greeter-package (see its openedge-project.json), so resolving
     * example.calculator also requires example.greeter to be findable.
     */
    private fun buildRegistry(): File {
        val registryDir = createTempDirectory("oepm-functional-test-registry").toFile()

        for ((fixtureDirName, registryName) in listOf(
            "calculator-package" to "example.calculator",
            "greeter-package" to "example.greeter",
        )) {
            val fixtureDir = File("src/functionalTest/resources/fixtures/$fixtureDirName")
            require(fixtureDir.exists()) { "Fixture not found — functionalTest must run from the repo root" }
            fixtureDir.copyRecursively(File(registryDir, registryName))
        }

        return registryDir
    }

    /** Builds a throwaway consumer project applying the plugin, pointed at the given registry. */
    private fun buildProject(registryDir: File, manifestJson: JSONObject): File {
        val projectDir = createTempDirectory("oepm-functional-test").toFile()
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "consumer-app-fixture"""",
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.erudys27.oepm")
            }

            oepm {
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(manifestJson.toString(2))
        return projectDir
    }

    private fun run(projectDir: File, vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)
            .build()

    private fun buildPathOf(projectDir: File): List<String> {
        val array = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONArray("buildPath")
        return (0 until array.length()).map { array.getJSONObject(it).getString("path") }
    }

    @Test
    fun `oepmInstall and oepmPropath run for real against the fixture packages`() {
        val registryDir = buildRegistry()

        // Declares only example.calculator directly - calculator-package
        // itself declares example.greeter, so a correct run resolves both
        // transitively (see DependencyResolver).
        val fixtureManifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, fixtureManifest)

        val installResult = run(projectDir, "oepmInstall")

        // consumer-app declares only example.calculator directly, but
        // calculator-package itself declares example.greeter — so a
        // correct run resolves both (see DependencyResolver).
        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected oepmInstall to report both the direct and transitive dependency, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "oepm_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected the resolved package's source to be copied into oepm_packages",
        )
        assertTrue(
            File(projectDir, "oepm_packages/example.greeter/src/example/greeter/Greeter.cls").exists(),
            "Expected the transitive dependency's source to be copied into oepm_packages too",
        )
        assertTrue(File(projectDir, "oepm.lock").exists(), "Expected oepmInstall to write oepm.lock")
        assertTrue(
            buildPathOf(projectDir).containsAll(
                listOf("oepm_packages/example.calculator/src", "oepm_packages/example.greeter/src"),
            ),
            "Expected oepmInstall to auto-add both the direct and transitive dependency to buildPath, got: ${buildPathOf(projectDir)}",
        )

        val propathResult = run(projectDir, "oepmPropath")
        val expectedCalculatorEntry = listOf("oepm_packages", "example.calculator", "src").joinToString(File.separator)
        val expectedGreeterEntry = listOf("oepm_packages", "example.greeter", "src").joinToString(File.separator)
        assertTrue(
            propathResult.output.contains(expectedCalculatorEntry) && propathResult.output.contains(expectedGreeterEntry),
            "Expected oepmPropath output to include both resolved dependencies' source paths, got:\n${propathResult.output}",
        )
    }

    @Test
    fun `a transitive dependency's version conflict with a direct dependency fails the install loudly`() {
        val registryDir = buildRegistry()
        // example.greeter is 1.0.0 in the registry; ask directly for
        // something incompatible with what calculator-package (which is
        // also being installed) requires (^1.0.0) — must fail, not
        // silently pick one.
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put(
                    "dependencies",
                    JSONObject()
                        .put("example.calculator", "^1.0.0")
                        .put("example.greeter", "^2.0.0"),
                )
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, manifest)

        val installResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("oepmInstall")
                .buildAndFail()

        assertTrue(
            installResult.output.contains("Version conflict") && installResult.output.contains("example.greeter"),
            "Expected a version-conflict failure naming example.greeter, got:\n${installResult.output}",
        )
    }

    @Test
    fun `oepmInstall -PoepmAdd adds and resolves a dependency in one step, without a prior manifest edit`() {
        val registryDir = buildRegistry()

        // No dependencies declared at all — the whole point of -PoepmAdd is
        // that the caller never touches the manifest by hand first.
        val emptyManifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, emptyManifest)

        val installResult = run(projectDir, "oepmInstall", "-PoepmAdd=example.calculator")

        assertTrue(
            installResult.output.contains("added \"example.calculator\": \"^1.0.0\" to dependencies"),
            "Expected oepmInstall to report the auto-picked version, got:\n${installResult.output}",
        )

        val dependencies = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies")
        assertTrue(
            dependencies.getString("example.calculator") == "^1.0.0",
            "Expected dependencies to contain the added package, got: $dependencies",
        )
        assertTrue(
            File(projectDir, "oepm_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected -PoepmAdd to also resolve the newly added dependency in the same run",
        )
        assertTrue(
            buildPathOf(projectDir).contains("oepm_packages/example.calculator/src"),
            "Expected buildPath to be updated in the same run, got: ${buildPathOf(projectDir)}",
        )
        assertTrue(
            File(projectDir, "oepm_packages/example.greeter/src/example/greeter/Greeter.cls").exists(),
            "Expected -PoepmAdd to also resolve example.calculator's own transitive dependency on example.greeter",
        )
    }

    @Test
    fun `a failed -PoepmAdd leaves the manifest's dependencies untouched`() {
        val registryDir = buildRegistry()

        // example.calculator is already declared, and (transitively) needs
        // example.greeter ^1.0.0. Adding example.greeter directly with an
        // incompatible version conflicts — the install must fail, and
        // critically, must NOT have written example.greeter into
        // dependencies before failing.
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put(
                    "buildPath",
                    JSONArray().put(JSONObject().put("type", "source").put("path", "src")),
                )
        val projectDir = buildProject(registryDir, manifest)

        val installResult =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("oepmInstall", "-PoepmAdd=example.greeter:^2.0.0")
                .buildAndFail()

        assertTrue(
            installResult.output.contains("Version conflict"),
            "Expected a version-conflict failure, got:\n${installResult.output}",
        )

        val dependencies = JSONObject(File(projectDir, "openedge-project.json").readText()).getJSONObject("dependencies")
        assertTrue(
            !dependencies.has("example.greeter"),
            "Expected example.greeter to NOT be written to dependencies after a failed install, got: $dependencies",
        )
        assertTrue(
            !File(projectDir, "oepm_packages").exists(),
            "Expected no oepm_packages to be written after a failed install",
        )
        assertTrue(
            !File(projectDir, "oepm.lock").exists(),
            "Expected no oepm.lock to be written after a failed install",
        )
    }
}
