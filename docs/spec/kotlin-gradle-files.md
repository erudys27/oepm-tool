# What each Kotlin/Gradle file does

A plain-language, file-by-file tour of this repo's `.kt` and `.kts` files —
written for a junior programmer who knows some programming but hasn't
necessarily used Gradle or Kotlin before. Everything about `.cls`, `.i`,
`.p`, `.lock`, and `openedge-project.json` files is left out on purpose;
this doc is only about the Kotlin/Gradle side.

Two definitions before the list:

- **Gradle** is the build tool oepm is written as a plugin for. A **task**
  is one named unit of work Gradle can run, e.g. `oepmInstall`. A `.kts`
  file is a Gradle build script written in the Kotlin language (Gradle
  also supports plain Groovy `.gradle` files, but this repo only uses
  Kotlin ones, hence "`.kts`" — Kotlin Script).
- **A Gradle plugin** is code that adds new tasks (and other capabilities)
  to a Gradle project. oepm itself *is* a Gradle plugin — this whole repo
  builds one JAR file that other Gradle projects can apply to gain the
  `oepmInstall` and `oepmPropath` tasks.

## Root project — building the oepm plugin itself

These files, at the repo root, define and build the oepm plugin. You
generally don't run these by hand — `oepm.bat` (below) does that for you.

- **`settings.gradle.kts`** — the very first file Gradle reads. Just names
  the project (`oepm`). One line.
- **`build.gradle.kts`** — the main build script for the plugin. It
  declares:
  - This project *is* a Gradle plugin (`java-gradle-plugin`), written in
    Kotlin, targeting Java 17.
  - The plugin's public identity: id `io.github.erudys27.oepm`, entry
    class `oepm.OepmPlugin` (the class Gradle runs when someone applies
    the plugin).
  - Its dependencies: the `org.json` library (for reading/writing JSON)
    and `kotlin-test` (for unit tests).
  - A second test source set called `functionalTest` (see below), wired
    so `./gradlew check` runs it alongside the normal unit tests.
- **`gradle.properties`** — a few project-wide settings. Currently just
  one line enabling Kotlin's official code style.
- **`gradle/wrapper/gradle-wrapper.properties`** — pins the exact Gradle
  version (8.10.2) so everyone building this project uses the same one,
  and says where to download it from. This is what makes `./gradlew` work
  without anyone installing Gradle by hand first.
- **`gradle/wrapper/gradle-wrapper.jar`** — a small program that reads the
  properties file above and downloads/runs the pinned Gradle version. You
  never edit this by hand.
- **`gradlew`** / **`gradlew.bat`** — the scripts you (or `oepm.bat`)
  actually run to invoke Gradle (`./gradlew` on Mac/Linux/git-bash,
  `gradlew.bat` on plain Windows cmd). They just launch the wrapper jar
  above.

## `src/main/kotlin/oepm/` — the plugin's actual logic

This is the real code. Read top-to-bottom, it roughly follows the order
things happen in when `oepmInstall` runs.

- **`OepmPlugin.kt`** — the plugin's entry point, the class named in
  `build.gradle.kts`. When a project applies
  `id("io.github.erudys27.oepm")`, this class's `apply()` function runs
  once and registers two Gradle tasks on that project:
  - **`oepmInstall`** — resolves the project's dependencies and installs
    them (full behavior described in the "install" walkthrough below).
  - **`oepmPropath`** — prints the project's PROPATH (the ordered list of
    source folders ABL needs to find `.cls`/`.p`/`.i` files), computed
    from `buildPath`.

  It also defines `OepmExtension`, a small settings object a project
  applying oepm can configure — right now just `registryRoot`, i.e.
  "where do I look for packages to install."

- **`manifest/Manifest.kt`** — a plain data class (fields only, no
  behavior) representing what got read out of a project's
  `openedge-project.json`: `name`, `version`, `packageName`,
  `dependencies`, `sourceRoots`. Think of it as the in-memory, typed
  version of that JSON file.
- **`manifest/ManifestReader.kt`** — has one job: open a
  `openedge-project.json` file and parse it into a `Manifest` object.
  Errors loudly (throws an exception) if the file is missing or has no
  `package_name`, rather than silently returning something broken.
- **`manifest/BuildPathUpdater.kt`** — after a dependency is resolved and
  its source copied in, this makes sure that dependency's source path is
  listed in the manifest's `buildPath`, so `oepmPropath` (and your ABL
  IDE) actually pick it up. It only ever *adds* entries — it never
  removes or reorders anything already there, so you're free to hand-edit
  `buildPath` and oepm won't clobber your changes.
- **`manifest/DependenciesUpdater.kt`** — writes a new entry into the
  manifest's `dependencies` map on disk. This is what `oepm install
  <package>` uses so you don't have to hand-edit the JSON yourself.

- **`propath/PropathGenerator.kt`** — small, pure function: turns a
  `Manifest`'s `sourceRoots` list into a list of absolute folder paths on
  disk. That list *is* the PROPATH. No file writing, no side effects.

- **`registry/Registry.kt`** — an *interface* (a contract, not an
  implementation) describing "something you can ask for a package by name
  and version." It also defines what a resolved package looks like
  (`ResolvedPackage`: name, version, source folder, and *project*
  folder — the project folder is what lets the resolver below find that
  package's own `openedge-project.json` and keep walking its
  dependencies).
- **`registry/LocalDirectoryRegistry.kt`** — the only real implementation
  of `Registry` right now. A "registry" here is just a folder on disk
  containing one subfolder per package, each with its own
  `openedge-project.json`. This class scans that folder to find a package
  by name and checks whether its version matches what was asked for.

- **`resolver/DependencyResolver.kt`** — the part that walks the *whole*
  dependency graph, not just the dependencies listed directly in one
  manifest. For every dependency it resolves, it also reads *that*
  package's own `openedge-project.json` and resolves its dependencies
  too, recursively, until nothing new shows up. Two safety checks along
  the way:
  - If the same package name gets resolved twice with incompatible
    version requirements (e.g. one dependency needs `^1.0.0` of
    something, another needs `^2.0.0` of the same package), it fails
    loudly instead of silently picking one.
  - If two packages depend on each other in a loop (A needs B, B needs
    A), it fails loudly instead of hanging forever.

- **`version/SemVer.kt`** — two small, related pieces of version-number
  logic in one file:
  - `SemVer` — parses and compares plain `X.Y.Z` version numbers.
  - `CaretRange` — implements npm-style `^X.Y.Z` ranges (e.g. `^1.2.3`
    matches anything from `1.2.3` up to, but not including, `2.0.0`).

## `src/test/kotlin/oepm/` — unit tests

One test file per main file above, mirroring the same package structure.
Each tests its counterpart in isolation — no real Gradle build involved,
just calling Kotlin functions directly and checking the results:

- **`manifest/ManifestReaderTest.kt`** — manifest parsing, including the
  "missing `package_name`" error case.
- **`manifest/BuildPathUpdaterTest.kt`** — adding entries, not duplicating
  ones that already exist, preserving existing ones.
- **`manifest/DependenciesUpdaterTest.kt`** — adding a dependency, adding
  alongside existing ones, overwriting an existing entry's version.
- **`registry/LocalDirectoryRegistryTest.kt`** — resolving a package,
  version-range mismatches, "package not found" errors, `findAny`.
- **`resolver/DependencyResolverTest.kt`** — a dependency's own transitive
  dependency gets resolved too; a "diamond" (two packages both depending
  on a third, compatible version) resolves to one shared copy; an
  incompatible diamond and a circular dependency both fail with a clear
  error instead of silently doing the wrong thing.
- **`version/CaretRangeTest.kt`** — caret-range matching, including npm's
  "stricter below 1.0.0" rule, and rejecting anything that isn't a caret
  range.

## `src/functionalTest/kotlin/oepm/` — functional test

- **`OepmPluginFunctionalTest.kt`** — different from the unit tests above:
  this one runs the *real* plugin through a *real* (throwaway) Gradle
  build, using Gradle's own `TestKit` tool. It copies both
  `demo/packages/calculator-package` *and* `greeter-package` into a
  temporary registry folder (calculator-package genuinely depends on
  greeter-package, so both are needed), builds a temporary consumer
  project that applies the oepm plugin, and actually runs `oepmInstall` /
  `oepmPropath` against it — checking real task output and real files on
  disk, not just Kotlin function calls. Covers the transitive-resolution
  case (consumer only declares calculator-package directly, but
  greeter-package still gets resolved and copied), a real
  version-conflict failure, the one-step "add and install" flow, and that
  a failed add-and-install doesn't leave a stray entry behind. This is
  what proves the plugin works end-to-end, not just that its pieces work
  individually.

## Root `oepm` / `oepm.bat` — a friendlier command-line wrapper

Not Kotlin or Gradle files themselves, but worth including since they're
the thing you actually type:

- **`oepm`** (bash) / **`oepm.bat`** (Windows cmd) — thin scripts that let
  you type `oepm install` or `oepm propath` instead of the full
  `./gradlew oepmInstall`. `oepm install <package>[:<versionSpec>]`
  forwards to a `-PoepmAdd=...` Gradle project property under the hood.
  These only work when run from a project that already applies the oepm
  plugin (like `demo/customer-app`) — they're not a standalone install of
  oepm itself.

## `demo/customer-app/` and `demo/exploration/consumer-app/` — real projects *using* the plugin

`.kts` files, separate from the ones above — each is a *different* Gradle
project (a demo app consuming oepm), not part of building oepm itself.
Both follow the same two-file pattern:

- **`settings.gradle.kts`** — uses `includeBuild(...)` to pull in the oepm
  plugin directly from this repo's own source, since oepm isn't published
  anywhere yet (this is called a "composite build" — once oepm is
  published for real, this would become a normal version-numbered plugin
  dependency instead). The relative path just has to reach the repo
  root — `../..` from `demo/customer-app`, `../../..` from
  `demo/exploration/consumer-app`.
- **`build.gradle.kts`** — applies the oepm plugin
  (`id("io.github.erudys27.oepm")`) and configures its `registryRoot` to
  point at `demo/packages/` — the folder holding every demo package
  (`calculator-package`, `greeter-package`, and friends). See
  `demo/packages/README.md`.

## Step by step: `oepm.bat install example.calculator`

Say you run this from `demo/exploration/consumer-app`. Here's exactly
what happens, file by file:

1. **`oepm.bat`** parses the command. `install` with an argument means:
   run `gradlew.bat oepmInstall "-PoepmAdd=example.calculator"`. It just
   forwards to Gradle — it does no resolving itself.
2. **`gradlew.bat`** launches the pinned Gradle version (via
   `gradle-wrapper.jar` / `gradle-wrapper.properties`) and tells it to run
   the `oepmInstall` task with the `oepmAdd` project property set to
   `example.calculator`.
3. Gradle reads **`settings.gradle.kts`** and **`build.gradle.kts`** in
   `consumer-app/`, which pull in the oepm plugin from this repo's source
   and apply it — running **`OepmPlugin.kt`**'s `apply()`, which registers
   the `oepmInstall` task (among others).
4. Gradle runs the `oepmInstall` task body, in **`OepmPlugin.kt`**:
   a. It reads `consumer-app/openedge-project.json` via
      **`ManifestReader.kt`**, producing a `Manifest`.
   b. It creates a **`LocalDirectoryRegistry.kt`** pointed at the
      configured `registryRoot` (`demo/packages/`).
   c. Since `-PoepmAdd=example.calculator` has no `:versionSpec`, it looks
      up `example.calculator` in the registry (via `findAny`) to find
      whatever version actually exists there, and turns it into a caret
      range like `^1.0.0`. Nothing is written to disk yet — this pair is
      only held in memory.
   d. It merges that pending package into the manifest's existing
      `dependencies` and hands the whole set to
      **`DependencyResolver.kt`**. The resolver resolves
      `example.calculator` against the registry, then reads *its*
      `openedge-project.json` in turn and resolves anything it depends on
      too (e.g. `example.greeter`, if calculator depends on it) — this
      repeats until the whole graph is resolved, checking for version
      conflicts and circular dependencies as it goes.
   e. For every resolved package, its source folder is copied into
      `consumer-app/oepm_packages/<packageName>/src`, overwriting
      whatever was there before.
   f. Only now, because resolution succeeded, does
      **`DependenciesUpdater.kt`** actually write `"example.calculator":
      "^1.0.0"` into `consumer-app/openedge-project.json`. If any step
      before this failed, this write never happens — the manifest is left
      untouched.
   g. `consumer-app/oepm.lock` is written from scratch, listing every
      resolved package's name, version, and source path.
   h. **`BuildPathUpdater.kt`** adds a `oepm_packages/<packageName>/src`
      entry to the manifest's `buildPath` for every resolved package that
      isn't already listed there.
   i. Gradle prints a summary line: how many dependencies were resolved.

## Step by step: `oepm.bat propath`

1. **`oepm.bat`** forwards this straight to
   `gradlew.bat oepmPropath` — no arguments needed.
2. **`gradlew.bat`** launches Gradle, which applies the plugin the same
   way as step 3 above, registering the `oepmPropath` task.
3. Gradle runs the `oepmPropath` task body, in **`OepmPlugin.kt`**:
   a. It reads `consumer-app/openedge-project.json` via
      **`ManifestReader.kt`** — this includes whatever `buildPath` was
      last written by `oepmInstall`.
   b. It calls **`PropathGenerator.kt`**, which takes the manifest's
      `sourceRoots` (i.e. `buildPath`) and turns each entry into an
      absolute folder path on disk. No files are read or written beyond
      the manifest — this is a pure, in-memory transformation.
   c. Gradle prints the resulting list of absolute paths, one per line —
      that's the PROPATH you'd feed to the ABL compiler/IDE.

Note that `oepmPropath` never re-resolves or re-copies anything — it just
reports what `buildPath` already says. If you've added a dependency to
`openedge-project.json` by hand without running `oepm install`, its
source won't be in `oepm_packages/` yet and it won't show up here either.
