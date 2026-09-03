# What each Kotlin/Gradle file does

A plain-language, file-by-file tour of this repo's `.kt` and `.kts` files —
written for a junior programmer who knows some programming but hasn't
necessarily used Gradle or Kotlin before. Everything about `.cls`, `.i`,
`.p`, `.lock`, and `openedge-project.json` files is left out on purpose;
this doc is only about the Kotlin/Gradle side.

This repo used to be the whole `oepm` monorepo — plugin, a `demo/` app,
and registry content all together. It's since been split (see README.md's
intro): this repo is just the plugin now. Real projects that *use* the
plugin live elsewhere — the demo/consumer app at
[openedge-package-manager](https://github.com/erudys27/openedge-package-manager),
and small throwaway fixture packages this repo carries itself for tests
(`src/functionalTest/resources/fixtures/`). Older docs in `docs/decisions/`
and `docs/research/` may still mention `demo/` — those are historical
records of the monorepo era and are left as-is on purpose, not updated.

Two definitions before the list:

- **Gradle** is the build tool oepm is written as a plugin for. A **task**
  is one named unit of work Gradle can run, e.g. `oepmInstall`. A `.kts`
  file is a Gradle build script written in the Kotlin language (Gradle
  also supports plain Groovy `.gradle` files, but this repo only uses
  Kotlin ones, hence "`.kts`" — Kotlin Script).
- **A Gradle plugin** is code that adds new tasks (and other capabilities)
  to a Gradle project. oepm itself *is* a Gradle plugin — this whole repo
  builds one JAR file that other Gradle projects can apply to gain the
  `oepmInstall`/`oepmPropath`/`oepmRegistryAdd` tasks.

## Root project — building the oepm plugin itself

These files, at the repo root, define and build the oepm plugin. You
generally don't run these by hand — `oepm`/`oepm.bat` (below) does that
for you.

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
    and `kotlin-test` (for unit tests). A separate `buildscript {}` block
    also pulls `org.json` onto the *script's own* compile-time classpath —
    needed because this file uses `org.json.JSONObject` directly in its
    own `scaffoldProject` task (see below), which is a different
    classpath than the plugin's compiled output.
  - A second test source set called `functionalTest` (see below), wired
    so `./gradlew check` runs it alongside the normal unit tests.
  - Publishing config (`maven-publish`) — see ADR-0008 — and the
    **`scaffoldProject`** task: not something the plugin itself registers
    (a not-yet-wired project has no build to run a task against yet), so
    it lives here instead, run against oepm-tool's own build with
    `-PtargetDir=<path>`. Generates or non-destructively patches a
    project's `openedge-project.json`, Gradle wrapper files (in `.oepm/`
    for a fresh project, at the root for an already-set-up one — see
    `scaffold/templates/` below), and `oepm-registries.properties`. This
    is what `oepm-init` calls under the hood.
- **`gradle.properties`** — a few project-wide settings. Currently just
  one line enabling Kotlin's official code style.
- **`gradle/wrapper/gradle-wrapper.properties`** — pins the exact Gradle
  version so everyone building this project uses the same one, and says
  where to download it from. This is what makes `./gradlew` work without
  anyone installing Gradle by hand first.
- **`gradle/wrapper/gradle-wrapper.jar`** — a small program that reads the
  properties file above and downloads/runs the pinned Gradle version. You
  never edit this by hand.
- **`gradlew`** / **`gradlew.bat`** — the scripts you (or `oepm`/`oepm.bat`)
  actually run to invoke Gradle (`./gradlew` on Mac/Linux/git-bash,
  `gradlew.bat` on plain Windows cmd). They just launch the wrapper jar
  above.

## `scaffold/templates/` — what `scaffoldProject` renders

Plain text templates with `{{TOKEN}}` placeholders, filled in by the
`scaffoldProject` task above. Not compiled, not Kotlin — just data:
`settings.gradle.kts.template`, `build.gradle.kts.template` (carries a
`{{PROJECT_ROOT_BLOCK}}` token, empty for a legacy root-level layout or
`projectRoot.set(file(".."))` for the `.oepm/` layout), `gradle.properties.template`,
`openedge-project.json.template`.

## `src/main/kotlin/oepm/` — the plugin's actual logic

This is the real code, grouped by package. Read the "install walkthrough"
below for how these actually fit together at runtime.

**`OepmPlugin.kt` — the entry point.** When a project applies
`id("io.github.erudys27.oepm")`, its `apply()` function runs once and
registers three Gradle tasks:
- **`oepmInstall`** — resolves the project's dependencies and installs
  them (full behavior in the walkthrough below). `-PoepmAdd=<package>[:<versionSpec>]`
  adds and resolves a new dependency in the same step.
- **`oepmPropath`** — prints the project's PROPATH, computed from
  `buildPath`.
- **`oepmRegistryAdd`** — appends a registry entry to
  `oepm-registries.properties` (`-PregistryPrefix=... -PcatalogUrl=...`),
  so a registry can be added without hand-editing `build.gradle.kts`.

It also defines `OepmExtension`, the `oepm {}` block a project configures:
`projectRoot` (where the actual ABL project lives — defaults to wherever
`build.gradle.kts` itself is; set to `file("..")` for the `.oepm/`
layout), `registryRoot` (the old, single-local-folder registry, still the
fallback when no `registries{}` are configured), `cacheDir` (where
fetched packages are cached — `~/.oepm/cache` by default), and the
`registries {}` container of named `GitRegistrySpec` entries
(`prefix`, `catalogUrl`, `catalogRef`).

**`manifest/`** — reading/writing a project's `openedge-project.json`:
- **`Manifest.kt`** — a plain data class representing what got read out of
  the file (`name`, `version`, `packageName`, `dependencies`,
  `sourceRoots`), plus `DependencySpec`: a dependency is either
  `Registry(versionSpec)` (a plain caret-range string, routed through
  whatever registry matches its prefix) or `DirectSource(repoUrl, ref)`
  (an inline `{repoUrl, ref}` object — fetched by git directly, no
  registry lookup at all).
- **`ManifestReader.kt`** — parses `openedge-project.json` into a
  `Manifest`. Auto-infers `package_name` from the project's own `.cls`
  files (via `PackageNameInferrer`, below) and writes it back if it was
  missing, rather than always requiring it hand-typed.
- **`ManifestWriter.kt`** — the shared "write this JSON back to disk,
  preserving formatting" used by the writers below.
- **`PackageNameInferrer.kt`** — scans a source root's `.cls` files for
  their declared OO ABL namespace (`class <namespace>.<Name>:`) and
  returns it, failing loudly if files disagree on namespace or none are
  found — used both by `ManifestReader`'s auto-infer and by
  `scaffoldProject`.
- **`BuildPathUpdater.kt`** — after a dependency is resolved and its
  source copied in, adds that dependency's `oepm_packages/.../src` path
  to the manifest's `buildPath` if it isn't already there. Additive
  only — never removes or reorders anything, so hand edits survive.
- **`DependenciesUpdater.kt`** — writes a new entry into the manifest's
  `dependencies` map on disk — what `-PoepmAdd=...` uses instead of
  requiring a hand-edit.

**`propath/PropathGenerator.kt`** — small, pure function: turns a
`Manifest`'s `sourceRoots` (its `buildPath` entries of `type: "source"`)
into a list of absolute folder paths on disk. That list *is* the PROPATH.
No file writing, no side effects.

**`registry/`** — implementations of `Registry` (in `Registry.kt`, along
with `ResolvedPackage` — name, version, source/project folders, and an
`installSubpath` hint for where `oepm_packages/` should nest it):
- **`LocalDirectoryRegistry.kt`** — the original v1 registry: a folder on
  disk, one subfolder per package. Still the fallback when no
  `registries{}`/`oepm-registries.properties` entries are configured.
- **`CatalogRegistry.kt`** — a remote registry backed by a small git
  "catalog" repo holding no package content itself, just one reference
  file per package version pointing at that package's own dedicated repo
  + tag. Fetches through `GitPackageFetcher` (below); sets
  `installSubpath` to `"<prefix>/<localName>"`.
- **`PrefixRoutingRegistry.kt`** — routes a package name to whichever
  configured registry's prefix it matches (longest prefix wins); no
  configured prefix matching is a loud error, not a silent fallback.
- **`PackageMatcher.kt`** — shared candidate-matching logic used by
  `LocalDirectoryRegistry`.
- **`RegistriesPropertiesFile.kt`** — reads/appends
  `oepm-registries.properties` entries (`<name>.prefix`/`<name>.catalogUrl`/`<name>.catalogRef`),
  the CLI-appendable alternative to hand-editing the `registries{}` DSL.

**`fetch/`** — git plumbing shared by `CatalogRegistry` and direct-source
dependencies:
- **`GitCli.kt`** — thin wrapper around shelling out to the system `git`.
- **`GitPackageFetcher.kt`** — fetches one package repo at a given ref.
  Caches as one bare clone per package (`_bare.git/` — no working-tree
  files, just git history) plus one `git worktree` checkout per version
  actually used (`<ref>/`). A version already checked out is reused
  as-is; a new version of an already-cached package is a local
  `git worktree add`, and only a genuinely new ref costs a real
  `git fetch` — cheaper than a fresh clone every time.

**`lock/` / `integrity/`** — `oepm.lock` and tamper detection:
- **`LockfileReader.kt`** — reads `oepm.lock`'s existing `resolved` entries.
- **`DirectoryHash.kt`** — content-hashes a resolved package's source
  folder (per-file hash → sorted manifest → one final hash).
- **`IntegrityChecker.kt`** — compares a freshly-resolved package's hash
  against `oepm.lock`'s existing entry for that same version; a mismatch
  (e.g. a git tag force-moved to different content) fails loudly instead
  of silently accepting the new content.

**`resolver/DependencyResolver.kt`** — walks the *whole* dependency graph,
not just what's declared directly in one manifest: for every resolved
package, its own `openedge-project.json` is read and its dependencies
resolved too, recursively. Checks along the way:
- The same key resolved twice with an incompatible requirement (a version
  range that doesn't match what's already resolved, or a direct-source
  spec with a different `repoUrl`/`ref`, or one spec being a registry
  dependency and the other direct-source) fails loudly.
- A circular dependency (A needs B, B needs A) fails loudly instead of
  looping forever.
- Once the whole graph is resolved, `checkNoNamespaceCollision` checks
  every resolved package's *real* `package_name` (independent of the key
  it was resolved under) for collisions — two different keys resolving to
  packages sharing the same real ABL namespace would silently shadow each
  other on PROPATH otherwise, so this fails loudly instead.

**`version/SemVer.kt`** — two small, related pieces of version-number
logic in one file: `SemVer` (parses/compares plain `X.Y.Z`), `CaretRange`
(npm-style `^X.Y.Z` matching).

## `src/test/kotlin/oepm/` — unit tests

One test file per main file above, mirroring the same package structure —
`fetch/`, `integrity/`, `lock/`, `manifest/`, `registry/`, `resolver/`,
`version/`. Each tests its counterpart in isolation (real temp
directories/git repos where relevant, no Gradle build involved).

## `src/functionalTest/kotlin/oepm/` — functional tests

Different from the unit tests above: these run the *real* plugin through
a *real* (throwaway) Gradle build, using Gradle's own `TestKit`.

- **`OepmPluginFunctionalTest.kt`** — applies the plugin via
  `includeBuild`/`withPluginClasspath()`, using this repo's own small
  fixture packages (`src/functionalTest/resources/fixtures/`), and
  actually runs `oepmInstall`/`oepmPropath`/`oepmRegistryAdd` — checking
  real task output and real files on disk, not just Kotlin function
  calls. Covers transitive resolution, version conflicts, the one-step
  "add and install" flow, merged `registries{}`/properties-file registry
  config, `oepm_packages/` nesting by registry prefix vs. direct-source,
  and `projectRoot` letting the ABL project live one level up from
  Gradle's own files (the `.oepm/` layout).
- **`PublishedPluginFunctionalTest.kt`** — proves the plugin can be
  applied the way a real, separate consumer repo would: by plugin id +
  version resolved from a Maven repository, not `includeBuild`/TestKit's
  classpath shortcut.

## Root `oepm` / `oepm.bat`, `cli/`, and `oepm-init` — the command-line layer

Not Kotlin or Gradle files themselves, but worth including since they're
what you actually type:

- **`oepm`** (bash) / **`oepm.bat`** (Windows) — thin scripts, scaffolded
  into *each* project by `scaffoldProject`, that translate `oepm install`/
  `oepm propath`/`oepm registry add` into the equivalent `./gradlew`
  calls. Find their target project by their own file location, so they
  work with zero global setup.
- **`cli/oepm`** / **`cli/oepm.bat`** — the same commands, but meant to be
  installed *once* (added to `PATH`) and reused across every project.
  Find their target project by walking upward from your current
  directory looking for `openedge-project.json` — the same way
  `git`/`npm` find their project root — which is what makes it safe to
  have exactly one copy on `PATH`.
- **`cli/install.sh`** / **`cli/install.ps1`** — one-time, idempotent
  setup that adds `cli/` to `PATH`.
- **`oepm-init`** / **`oepm-init.bat`** — interactive wrapper: prompts for
  registries, calls `scaffoldProject` against your current directory, and
  offers to run the `cli/install` script too.

## Step by step: `oepm install ba.calculator`

Say a consumer project has `registries { create("ba") { ... } }`
configured and declares `"ba.calculator": "^1.0.2"`. Here's roughly what
happens:

1. **`oepm`/`oepm.bat`/`cli/oepm`** forwards to
   `gradlew oepmInstall "-PoepmAdd=ba.calculator"` (or with no `-PoepmAdd`
   at all, for a plain `oepm install` re-resolving what's already
   declared).
2. **`gradlew`** launches the pinned Gradle version and runs the
   `oepmInstall` task.
3. In **`OepmPlugin.kt`**'s `oepmInstall` body:
   a. Reads `openedge-project.json` via **`ManifestReader.kt`**.
   b. Builds the configured `Registry` — merging `registries{}` and
      `oepm-registries.properties` entries into one
      **`PrefixRoutingRegistry.kt`** (or falling back to
      **`LocalDirectoryRegistry.kt`** if neither is configured).
   c. If `-PoepmAdd` had no explicit `:versionSpec`, looks up the package
      via the registry's `findAny` to pick whatever version exists and
      turns it into a caret range. Nothing written to disk yet.
   d. Hands the full dependency set to **`DependencyResolver.kt`**, which
      routes `ba.calculator` to the `ba` registry (via
      `PrefixRoutingRegistry` → **`CatalogRegistry.kt`**), fetches it
      through **`GitPackageFetcher.kt`**'s bare-clone-plus-worktree
      cache, then reads *its* `openedge-project.json` and resolves
      whatever it depends on too — recursively, checking version
      conflicts, circular dependencies, and namespace collisions along
      the way.
   e. Each resolved package is hashed (**`DirectoryHash.kt`**) and
      checked against `oepm.lock`'s existing entry for that version
      (**`IntegrityChecker.kt`**) *before* anything is copied — a tampered
      or force-moved tag fails loudly here, first.
   f. Each resolved package's source is copied into
      `oepm_packages/<installSubpath>/src` — nested by registry prefix
      (`oepm_packages/ba/calculator/src`) or under `_direct/` for a
      direct-source dependency, overwriting whatever was there before.
   g. Only now, because resolution succeeded, does
      **`DependenciesUpdater.kt`** write the new dependency into
      `openedge-project.json`. If any earlier step failed, this write
      never happens.
   h. `oepm.lock` is written from scratch: every resolved package's
      version, cache source path, and integrity hash.
   i. **`BuildPathUpdater.kt`** adds each resolved package's
      `oepm_packages/.../src` path to `buildPath`, if not already there.
   j. Gradle prints a summary line: how many dependencies were resolved.

## Step by step: `oepm propath`

1. **`oepm`/`oepm.bat`/`cli/oepm`** forwards straight to
   `gradlew oepmPropath` — no arguments needed.
2. **`gradlew`** launches Gradle, applying the plugin the same way as
   above, registering the `oepmPropath` task.
3. In **`OepmPlugin.kt`**'s `oepmPropath` body:
   a. Reads `openedge-project.json` via **`ManifestReader.kt`** — this
      includes whatever `buildPath` was last written by `oepmInstall`.
   b. Calls **`PropathGenerator.kt`**, which turns the manifest's
      `sourceRoots` into absolute folder paths. No files read or written
      beyond the manifest — a pure, in-memory transformation.
   c. Gradle prints the resulting list of absolute paths, one per line —
      that's the PROPATH you'd feed to the ABL compiler/IDE.

Note that `oepmPropath` never re-resolves or re-copies anything — it just
reports what `buildPath` already says. If you've added a dependency to
`openedge-project.json` by hand without running `oepm install`, its
source won't be in `oepm_packages/` yet and it won't show up here either.
