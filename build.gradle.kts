plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.9.24"
}

group = "io.github.erudys27"
version = "0.1.0-SNAPSHOT"

// java-gradle-plugin + maven-publish together auto-register a
// "pluginMaven" publication (the plugin's own jar/pom) plus a marker
// publication per entry in gradlePlugin{} below (what lets a consumer
// resolve by plugin id instead of group:artifact coordinates) - no
// publications{} block needed here.
//
// Where oepmPublishRepoUrl actually points is deliberately not decided
// here - default is a local, disposable folder so `./gradlew publish`
// works out of the box for testing. See MULTI-REPO-SETUP-PLAN.md for the
// real target (a git-repo-hosted Maven repo).
publishing {
    repositories {
        maven {
            name = "oepm"
            url =
                uri(
                    (findProperty("oepmPublishRepoUrl") as String?)
                        ?: layout.buildDirectory.dir("local-maven-repo").get().asFile.toURI().toString(),
                )
        }
    }
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

val functionalTest: SourceSet by sourceSets.creating

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])

gradlePlugin {
    plugins {
        create("oepm") {
            id = "io.github.erudys27.oepm"
            implementationClass = "oepm.OepmPlugin"
            displayName = "oepm"
            description = "Dependency management, versioning, and PROPATH generation for Progress OpenEdge ABL."
        }
    }
    testSourceSets.add(functionalTest)
}

dependencies {
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
    "functionalTestImplementation"(kotlin("test"))
    "functionalTestImplementation"(gradleTestKit())
    "functionalTestImplementation"("org.json:json:20250517")
}

tasks.test {
    useJUnitPlatform()
}

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        description = "Runs the plugin against real Gradle builds via TestKit."
        group = "verification"
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnitPlatform()
        // Fixtures reference demo/ files relative to the repo root.
        workingDir = rootDir
        // PublishedPluginFunctionalTest applies the plugin via a real
        // Maven repository lookup (no withPluginClasspath()/includeBuild
        // shortcut) - it needs the current version actually published
        // somewhere Gradle's normal plugin resolution can find it first.
        // mavenLocal() is used rather than oepmPublishRepoUrl's target
        // here since it needs no configuration and is always available.
        dependsOn("publishToMavenLocal")
        systemProperty("oepmPluginVersion", version.toString())
    }

tasks.check {
    dependsOn(functionalTestTask)
}
