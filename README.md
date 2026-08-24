# oepm — a package manager for Progress OpenEdge ABL

> Renamed from the earlier working name "ppm" — see
> [docs/decisions/0005-naming.md](docs/decisions/0005-naming.md) for why.
> `oepm` hasn't yet been checked against npm, PyPI, crates.io, or the
> relevant JVM package coordinates (Maven Central group ID) — do that
> before registering a domain or publishing anything under this name.

## What this is

An experiment in bringing dependency management, versioning, and modular
code distribution to OpenEdge ABL — a language and runtime that has none of
this natively. See `docs/research/` for the comparative analysis (npm, pip,
CPAN-era package managers, Maven/Ivy, and the existing OpenEdge tooling
ecosystem) that this design is based on.

## Status

The core loop works end to end: declare a dependency (or let `oepm` do it
for you), resolve it — transitively, including a resolved package's own
declared dependencies, with a version-conflict check across the whole
graph — against the local-directory registry, copy sources in, wire them
into PROPATH. See `docs/decisions/` for what's been decided and why, and
the open questions at the bottom of this file for what hasn't. Still
missing: real Gradle/Ivy-based resolution (a hand-written version matcher
is used instead, see the note under "Getting started"), integrity
hashing, multi-version registry support, and include-collision linting.

## Repo layout

```
docs/
  decisions/     ADRs — one file per decision, numbered, with status
  spec/          the actual manifest/lockfile/PROPATH specification
  research/      background analysis that informed the decisions
demo/packages/
  calculator-package/  sample OO ABL package (a dependency) — plain ABL, no Gradle files, lives in the registry;
                        itself declares a dependency on greeter-package (see its openedge-project.json), to
                        exercise transitive resolution
  greeter-package/     another sample OO ABL package (a dependency) — same as above
  calculator-one-package/, calculator-two-package/  both depend on greeter-package, with
                        incompatible version ranges (^1.0.0 vs ^2.0.0)
  double-calculator-package/  depends on both of the above — installing it is what actually
                        triggers the version-conflict failure (see demo/packages/README.md)
demo/exploration/
  consumer-app/         sample OO ABL app illustrating PROPATH/include collisions (see its WALKTHROUGH.md) —
                        declares only calculator-package directly; greeter-package is resolved transitively.
                        Applies the oepm plugin for real (settings.gradle.kts/build.gradle.kts), so
                        `oepm install`/`oepm propath` can be run against it directly
demo/customer-app/     a more fleshed-out demo app (several classes, an invoice/greeting scenario) for
                        showing oepm to someone else — see its own README
src/main/kotlin/oepm/
  OepmPlugin.kt      Gradle plugin entrypoint — registers oepmInstall/oepmPropath tasks,
                     supports -PoepmAdd=<package_name>[:<versionSpec>] to add + resolve in one step
  manifest/          reads/writes oepm's manifest fields in openedge-project.json
                     (ManifestReader, BuildPathUpdater, DependenciesUpdater)
  propath/           turns a project's buildPath into an ordered, absolute PROPATH
  registry/          local-directory registry: finds a package by package_name, matches version ranges
  resolver/          resolves a dependency graph transitively (a resolved package's own
                     dependencies are resolved too), with a version-conflict check per package name
  version/           SemVer + npm-style caret range matching
src/test/kotlin/oepm/           unit tests, mirrors src/main/kotlin/oepm layout
src/functionalTest/kotlin/oepm/ Gradle TestKit tests — apply the real plugin to a
                                 throwaway project and run its tasks for real, using
                                 copies of demo/packages/ as fixtures
oepm / oepm.bat        thin CLI wrapper — translates `oepm install <package>` /
                        `oepm propath` into the equivalent ./gradlew calls
build.gradle.kts       plugin build config (Kotlin, Java 17 toolchain)
```

The Gradle wrapper (`gradlew`/`gradlew.bat`) is checked in.

## Scope for v1 (see ADRs for full reasoning)

- OO ABL packages only (`.cls`/`.i` under a namespace directory) — no
  procedural `.p`/`.w` support yet.
- Source packages are the primary resolved/locked artifact; compiled `.pl`
  output is a separate, optional, OE-version-specific build step.
- Database dependencies are entirely out of scope for v1 — not merely
  unmanaged (see [ADR-0003](docs/decisions/0003-db-deps-declared-not-managed.md),
  rejected).
- Registry: local filesystem directory only. No HTTP registry, no auth.
- Publishing: minimal — `maven-publish` to a plain git-repo-hosted Maven
  repository (no hosted registry service, no auth) — see
  [ADR-0008](docs/decisions/0008-plugin-publishing-v1.md).
- Implementation language: Kotlin, as a Gradle plugin reusing Gradle's
  dependency-resolution engine (see ADR-0001).
- Manifest: no separate `oepm.json` — oepm-owned keys (`package_name`,
  `dependencies`) live directly in `openedge-project.json` (see
  [manifest-schema.md](docs/spec/manifest-schema.md)).
- Dependency version ranges: caret ranges (`^1.0.0`), same syntax as npm.

## Per-machine setup

Two ways to consume the plugin — pick based on whether you're developing
`oepm` itself or just using it.

**Option A — `includeBuild` (source, for developing `oepm` itself):**

1. **Clone this repo.**
2. **(Optional) Add this repo's root to your `PATH`** so the `oepm`/`oepm.bat`
   wrapper scripts can be run without a relative-path prefix from any
   directory:
   ```powershell
   $repoRoot = "<path to your clone>"
   $currentUserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
   [Environment]::SetEnvironmentVariable("PATH", "$currentUserPath;$repoRoot", "User")
   ```
   Open a new terminal afterward — PATH changes don't apply to already-open
   sessions. This is per-user, per-machine; it isn't part of the repo and
   doesn't travel with `git clone`.
3. **Every consumer project's `settings.gradle.kts`** needs
   `pluginManagement { includeBuild("<path-to-your-clone-of-this-repo>") }`
   pointing at wherever *that person* cloned this repo — see
   `demo/customer-app/settings.gradle.kts` for the pattern. In this repo's
   demo projects, that path is a `gradle.properties` value
   (`oepmToolPath`), not hardcoded — override it per-clone instead of
   editing `settings.gradle.kts`. This only works within a team sharing
   consistent clone locations (or a monorepo); it isn't viable for an
   arbitrary external user.

**Option B — published coordinate (for actually using `oepm`, once it's
been published somewhere — see [ADR-0008](docs/decisions/0008-plugin-publishing-v1.md)):**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { maven(url = "<wherever oepm was published>") }
}

// build.gradle.kts
plugins {
    id("io.github.erudys27.oepm") version "x.y.z"
}
```

No path plumbing, no PATH setup needed for this part — just the URL of
wherever the plugin got published (see
[MULTI-REPO-SETUP-PLAN.md](MULTI-REPO-SETUP-PLAN.md) for the actual
hosting plan). `./gradlew publish` publishes the current version to a
local, disposable folder by default (`-PoepmPublishRepoUrl=...` to target
somewhere real).

## Getting started

From inside `demo/customer-app` (with the repo root on `PATH`, per step 2
above — use `.bat` explicitly in PowerShell):

```
oepm.bat install    # resolves example.calculator, and transitively, example.greeter
oepm.bat propath    # print the generated PROPATH
```

Without the `PATH` step, use the relative path instead:
`../../oepm install` (bash) or `..\..\oepm.bat install` (PowerShell).

Or directly via Gradle, from the repo root:

```
./gradlew -p demo/customer-app oepmInstall
./gradlew -p demo/customer-app oepmPropath
```

`customer-app`'s `settings.gradle.kts` pulls the plugin in via
`includeBuild` by default (Option A above) — the actual path comes from
`gradle.properties`' `oepmToolPath`, not a hardcoded literal, so pointing
this at a separately-cloned tool repo is a one-line properties edit. Its
`registryRoot` is the same story via `oepmRegistryRoot`, currently
pointing at `demo/packages/`, where every demo package
(`calculator-package`, `greeter-package`, and friends) lives, so any of
them can be installed by its `package_name`. See
`demo/customer-app/README.md` for what this project actually does, and
`demo/exploration/consumer-app` for the plugin's original, more minimal
end-to-end fixture (also used by `./gradlew functionalTest` below).

To verify the plugin in isolation instead (no dependency on demo state):

```
./gradlew functionalTest
```

This includes `PublishedPluginFunctionalTest`, which proves Option B
above actually works — it publishes the plugin and applies it to a
throwaway consumer by `id(...) version "..."` only, with no
`includeBuild` and no TestKit classpath shortcut.

**Known deviation from ADR-0001**: version matching (`oepm/version/`) and
graph resolution (`oepm/resolver/`) are hand-written, not delegated to
Gradle/Ivy's resolver as that ADR planned. Worth raising with the team
before treating this as settled — the functionality (transitive
resolution, version-conflict detection) is there, but ADR-0001's premise
was reusing Gradle/Ivy's own resolver rather than writing one.

## Open questions

- Whether `oepm propath` should also emit a `.pf` parameter file, since
  that's what devs actually pass to `_progres`/`prowin`.
- `package_name` uniqueness is enforced within one registry directory
  (`LocalDirectoryRegistry` fails loudly on a collision), but not across
  separate registries — see [manifest-schema.md](docs/spec/manifest-schema.md).
- Whether oepm should warn/fail when a generated PROPATH would shadow an
  entry already present in `openedge-project.json`'s `buildPath` — see
  [propath-generation.md](docs/spec/propath-generation.md).
- `oepm.lock`'s `source` field is currently written as an absolute
  filesystem path (not portable across machines/checkouts) — needs a
  relative or `local:///`-style scheme instead, per
  [lockfile-format.md](docs/spec/lockfile-format.md).
- Circular-dependency and version-conflict errors from `oepm/resolver/`
  are raw exception messages surfaced through Gradle's build failure
  output — no dedicated, friendlier error reporting yet.
