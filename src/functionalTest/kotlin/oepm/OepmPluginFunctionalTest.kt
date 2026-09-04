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

    // --- registries{} DSL + oepm-registries.properties merging ---

    /**
     * A project with one registry from the registries{} DSL and one from
     * oepm-registries.properties - neither URL needs to be real: this
     * only exercises PrefixRoutingRegistry's own routing/merge logic
     * (surfaced via its "no configured prefix matches" error listing both
     * prefixes), never an actual fetch.
     */
    private fun buildMergedRegistriesProject(): File {
        val projectDir = createTempDirectory("oepm-functional-test-merge").toFile()

        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "merge-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.erudys27.oepm")
            }

            oepm {
                registries {
                    create("x") {
                        prefix.set("x.")
                        catalogUrl.set("https://example.invalid/x.git")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("oepm-registries.properties").writeText(
            """
            y.prefix=y.
            y.catalogUrl=https://example.invalid/y.git
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "merge-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.merge")
                .put("dependencies", JSONObject().put("z.something", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        return projectDir
    }

    @Test
    fun `registries from the DSL and oepm-registries properties are both applied`() {
        val projectDir = buildMergedRegistriesProject()

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("oepmInstall")
                .buildAndFail()

        assertTrue(
            result.output.contains("x.") && result.output.contains("y."),
            "Expected both the DSL registry (x.) and the properties-file registry (y.) to be " +
                "configured, got:\n${result.output}",
        )
    }

    @Test
    fun `a prefix declared in both the DSL and oepm-registries properties fails loudly`() {
        val projectDir = buildMergedRegistriesProject()
        // Redeclare the DSL's "x." prefix under a different name in the properties file too.
        projectDir.resolve("oepm-registries.properties").appendText(
            "\nconflict.prefix=x.\nconflict.catalogUrl=https://example.invalid/conflict.git\n",
        )

        val result =
            GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("oepmInstall")
                .buildAndFail()

        assertTrue(
            result.output.contains("Duplicate registry prefix"),
            "Expected a duplicate-prefix failure, got:\n${result.output}",
        )
    }

    // --- projectRoot != Gradle's own project directory ---

    @Test
    fun `projectRoot lets the ABL project live one level up from Gradle's own files`() {
        val registryDir = buildRegistry()
        val registryPath = registryDir.absolutePath.replace("\\", "/")

        // Gradle's own files (settings.gradle.kts/build.gradle.kts) live in
        // a subfolder; openedge-project.json/src/etc. live at abRoot, one
        // level up - the ".oepm/" layout scaffoldProject can generate.
        val abRoot = createTempDirectory("oepm-functional-test-projectroot").toFile()
        val gradleFilesDir = File(abRoot, ".oepm").apply { mkdirs() }

        gradleFilesDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "projectroot-fixture"""")
        gradleFilesDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.erudys27.oepm")
            }

            oepm {
                projectRoot.set(file(".."))
                registryRoot.set(file("$registryPath"))
            }
            """.trimIndent(),
        )
        abRoot.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "projectroot-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )

        val installResult =
            GradleRunner.create()
                .withProjectDir(gradleFilesDir)
                .withPluginClasspath()
                .withArguments("oepmInstall")
                .build()

        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected both the direct and transitive dependency resolved, got:\n${installResult.output}",
        )
        assertTrue(
            File(abRoot, "oepm_packages/example.calculator/src/example/calculator/Calculator.cls").exists(),
            "Expected oepm_packages to be written at the ABL project root (abRoot), not inside .oepm/",
        )
        assertTrue(!File(gradleFilesDir, "oepm_packages").exists(), "Expected no oepm_packages inside .oepm/")
        assertTrue(File(abRoot, "oepm.lock").exists(), "Expected oepm.lock at abRoot")
        assertTrue(!File(gradleFilesDir, "oepm.lock").exists(), "Expected no oepm.lock inside .oepm/")

        val propathResult =
            GradleRunner.create()
                .withProjectDir(gradleFilesDir)
                .withPluginClasspath()
                .withArguments("oepmPropath")
                .build()
        val expectedSrcEntry = File(abRoot, "src").absolutePath
        assertTrue(
            propathResult.output.contains(expectedSrcEntry),
            "Expected oepmPropath to resolve buildPath entries against abRoot, got:\n${propathResult.output}",
        )
    }

    // --- oepm_packages/ nested-by-prefix layout ---

    /**
     * functionalTest doesn't compile against the plugin's main sourceSet
     * (it only exercises the plugin via GradleRunner/withPluginClasspath),
     * so oepm.fetch.GitCli isn't visible here - a plain ProcessBuilder call
     * does the same job for building fixture git repos.
     */
    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "git ${args.joinToString(" ")} failed (exit $exitCode):\n$output" }
    }

    private fun gitPackageRepo(root: File, folderName: String, packageName: String, version: String): File {
        val dir = File(root, folderName)
        dir.mkdirs()
        git(dir, "init", "-b", "main")
        git(dir, "config", "user.email", "oepm-test@example.com")
        git(dir, "config", "user.name", "oepm test")
        File(dir, "openedge-project.json").writeText(
            JSONObject()
                .put("name", "$folderName-project")
                .put("version", version)
                .put("package_name", packageName)
                .put("dependencies", JSONObject())
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        val classDir = File(dir, "src/${packageName.replace('.', '/')}")
        classDir.mkdirs()
        val className = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        File(classDir, "$className.cls").writeText("class $packageName.$className:\nend class.")
        git(dir, "add", "-A")
        git(dir, "commit", "-m", "initial")
        git(dir, "tag", "v$version")
        return dir
    }

    @Test
    fun `oepm_packages nests a catalog-routed package by its registry prefix, and a direct-source one under _direct`() {
        val remotesRoot = createTempDirectory("oepm-functional-test-nested-remotes").toFile()

        val greeterRepo = gitPackageRepo(remotesRoot, "greeter-repo", "greeter", "1.0.1")
        val calculatorRepo = gitPackageRepo(remotesRoot, "calculator-repo", "calculator", "1.0.0")
        // calculator depends on greeter directly by source (no registry involved).
        File(calculatorRepo, "openedge-project.json").writeText(
            JSONObject()
                .put("name", "calculator-repo-project")
                .put("version", "1.0.0")
                .put("package_name", "calculator")
                .put(
                    "dependencies",
                    JSONObject().put(
                        "greeter",
                        JSONObject().put("repoUrl", greeterRepo.absolutePath.replace("\\", "/")).put("ref", "v1.0.1"),
                    ),
                )
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )
        git(calculatorRepo, "add", "-A")
        git(calculatorRepo, "commit", "-m", "declare direct-source dependency on greeter")
        // gitPackageRepo() already tagged v1.0.0 at the initial commit - move it to
        // this one, which is the one that actually declares the greeter dependency.
        git(calculatorRepo, "tag", "-f", "v1.0.0")

        val catalogDir = File(remotesRoot, "catalog")
        catalogDir.mkdirs()
        git(catalogDir, "init", "-b", "main")
        git(catalogDir, "config", "user.email", "oepm-test@example.com")
        git(catalogDir, "config", "user.name", "oepm test")
        File(catalogDir, "packages/calculator").mkdirs()
        File(catalogDir, "packages/calculator/1.0.0.json").writeText(
            JSONObject()
                .put("repoUrl", calculatorRepo.absolutePath.replace("\\", "/"))
                .put("version", "1.0.0")
                .put("ref", "v1.0.0")
                .toString(2),
        )
        git(catalogDir, "add", "-A")
        git(catalogDir, "commit", "-m", "add calculator 1.0.0")

        val projectDir = createTempDirectory("oepm-functional-test-nested-project").toFile()
        // Without an explicit cacheDir, this would default to the real
        // ~/.oepm/cache - fine normally, but this test's package names
        // ("ba/calculator", "greeter") can collide with genuinely
        // different content already cached there from real, live use of
        // this same machine, so a throwaway temp dir keeps this test
        // fully isolated.
        val cacheDir = createTempDirectory("oepm-functional-test-nested-cache").toFile()
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "nested-fixture"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.erudys27.oepm")
            }

            oepm {
                cacheDir.set(file("${cacheDir.absolutePath.replace("\\", "/")}"))
                registries {
                    create("ba") {
                        prefix.set("ba.")
                        catalogUrl.set("${catalogDir.absolutePath.replace("\\", "/")}")
                    }
                }
            }
            """.trimIndent(),
        )
        projectDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "nested-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("ba.calculator", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
                .toString(2),
        )

        val installResult = run(projectDir, "oepmInstall")

        assertTrue(
            installResult.output.contains("resolved 2 dependencies"),
            "Expected both ba.calculator and its transitive greeter dependency resolved, got:\n${installResult.output}",
        )
        assertTrue(
            File(projectDir, "oepm_packages/ba/calculator/src/calculator/Calculator.cls").exists(),
            "Expected the catalog-routed package to be nested under oepm_packages/ba/calculator",
        )
        assertTrue(
            File(projectDir, "oepm_packages/_direct/greeter/src/greeter/Greeter.cls").exists(),
            "Expected the direct-source dependency to be nested under oepm_packages/_direct/greeter",
        )
        assertTrue(
            buildPathOf(projectDir).containsAll(
                listOf("oepm_packages/ba/calculator/src", "oepm_packages/_direct/greeter/src"),
            ),
            "Expected buildPath to reference the nested paths, got: ${buildPathOf(projectDir)}",
        )
    }

    // --- buildPath "test" type ---

    @Test
    fun `oepmPropath only includes buildPath test entries when -PoepmIncludeTests is passed`() {
        val registryDir = buildRegistry()
        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray()
                        .put(JSONObject().put("type", "source").put("path", "src"))
                        .put(JSONObject().put("type", "test").put("path", "test")),
                )
        val projectDir = buildProject(registryDir, manifest)
        File(projectDir, "src").mkdirs()
        File(projectDir, "test").mkdirs()

        val expectedSrcEntry = File(projectDir, "src").absolutePath
        val expectedTestEntry = File(projectDir, "test").absolutePath

        val withoutFlag = run(projectDir, "oepmPropath")
        assertTrue(withoutFlag.output.contains(expectedSrcEntry), "Expected source root in output, got:\n${withoutFlag.output}")
        assertTrue(
            !withoutFlag.output.contains(expectedTestEntry),
            "Expected test root NOT in output without -PoepmIncludeTests, got:\n${withoutFlag.output}",
        )

        val withFlag = run(projectDir, "oepmPropath", "-PoepmIncludeTests")
        assertTrue(
            withFlag.output.contains(expectedSrcEntry) && withFlag.output.contains(expectedTestEntry),
            "Expected both source and test roots with -PoepmIncludeTests, got:\n${withFlag.output}",
        )
    }

    @Test
    fun `a dependency's own test entries are never copied into oepm_packages or added to a consumer's buildPath`() {
        val registryDir = createTempDirectory("oepm-functional-test-registry-hastests").toFile()
        val packageDir = File(registryDir, "example.hastests")
        packageDir.resolve("src/example/hastests").mkdirs()
        packageDir.resolve("src/example/hastests/Thing.cls").writeText("class example.hastests.Thing:\nend class.\n")
        packageDir.resolve("test").mkdirs()
        packageDir.resolve("test/ThingTest.cls").writeText("class ThingTest:\nend class.\n")
        packageDir.resolve("openedge-project.json").writeText(
            JSONObject()
                .put("name", "hastests-package")
                .put("version", "1.0.0")
                .put("package_name", "example.hastests")
                .put("dependencies", JSONObject())
                .put(
                    "buildPath",
                    JSONArray()
                        .put(JSONObject().put("type", "source").put("path", "src"))
                        .put(JSONObject().put("type", "test").put("path", "test")),
                )
                .toString(2),
        )

        val manifest =
            JSONObject()
                .put("name", "consumer-app-fixture")
                .put("version", "1.0.0")
                .put("package_name", "example.consumer")
                .put("dependencies", JSONObject().put("example.hastests", "^1.0.0"))
                .put("buildPath", JSONArray().put(JSONObject().put("type", "source").put("path", "src")))
        val projectDir = buildProject(registryDir, manifest)

        run(projectDir, "oepmInstall")

        assertTrue(
            File(projectDir, "oepm_packages/example.hastests/src/example/hastests/Thing.cls").exists(),
            "Expected the dependency's source to be copied into oepm_packages",
        )
        assertTrue(
            !File(projectDir, "oepm_packages/example.hastests/test").exists(),
            "Expected the dependency's own test folder to never be copied into oepm_packages at all",
        )
        assertTrue(
            "oepm_packages/example.hastests/test" !in buildPathOf(projectDir),
            "Expected no test-folder entry added to the consumer's buildPath, got: ${buildPathOf(projectDir)}",
        )
    }
}
