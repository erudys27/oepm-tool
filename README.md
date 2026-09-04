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

This repo used to be the whole `oepm` monorepo (plugin + demo app +
registry content together). It's since been split — this repo is just the
plugin itself now. The demo/consumer app lives at
[github.com/erudys27/openedge-package-manager](https://github.com/erudys27/openedge-package-manager),
and packages/registries are their own separate repos (see "Remote
registries" below).

## Status

The core loop works end to end, resolving from real, remote, git-hosted
registries — not just a local folder:

- **Multi-registry, prefix-routed resolution**: any number of independent
  registries can be configured, each with its own routing prefix (e.g.
  `ba.` → one registry, `cw.` → another). A dependency's prefix picks
  which registry it's fetched from (`oepm/registry/PrefixRoutingRegistry`).
- **Catalog-based registries**: a registry is a small git "catalog" repo
  holding no package content of its own — just one reference file per
  package version (`packages/<name>/<version>.json`) pointing at that
  package's own dedicated repo + tag. Fetching goes through a
  bare-clone-plus-`git worktree` cache — one clone per package, one
  worktree per version actually used, so repeat/multi-version fetches are
  local instead of re-cloning (`oepm/registry/CatalogRegistry`,
  `oepm/fetch/GitPackageFetcher`).
- **Direct-source dependencies**: a package's own manifest can depend on
  another package by inline `{repoUrl, ref}` instead of a registry lookup
  — no catalog entry needed for it at all. Always keyed by its own bare
  declared name (no inherited prefix — see `docs/spec/manifest-schema.md`
  for why that was tried and dropped).
- **Transitive resolution**: a resolved package's own declared
  dependencies are resolved too, recursively, with a version-conflict
  check across the whole graph (`oepm/resolver/DependencyResolver`).
- **Integrity verification**: `oepm.lock` records a real content hash per
  package (`oepm/integrity/DirectoryHash`); reinstalling an
  already-locked version whose registry content has since changed (e.g. a
  git tag force-moved to different content) fails loudly instead of
  silently accepting it (`oepm/lock/IntegrityChecker`).
- **PROPATH namespace-collision detection**: two resolved packages sharing
  the same real OO ABL namespace (`package_name`), even under different
  resolution keys, fail loudly at resolve time instead of silently
  shadowing each other on PROPATH.
- Backward compatible: a project with no `registries {}` configured still
  resolves against a plain local-directory registry
  (`oepm/registry/LocalDirectoryRegistry`), the original v1 behavior.
- **`buildPath` test entries**: `type: "test"` entries are excluded from
  `oepm propath`'s output by default, included with `oepm propath --tests`.
  A dependency's own test entries are never read at all when it's
  fetched, so they can't leak into a consumer's `oepm_packages/` or
  PROPATH either way.

See `docs/decisions/` for what's been decided and why, and the open
questions at the bottom of this file for what hasn't. Still missing: real
Gradle/Ivy-based resolution (a hand-written version matcher is used
instead, see "Known deviation from ADR-0001" below), multi-version
registry support (the catalog layout is prepped for it, but there's no
version-selection logic yet), include-collision linting, and an
`npm prune`-equivalent (an entry that stops being part of the resolved
graph is never automatically removed from `oepm_packages/`/`buildPath`).

## Repo layout

```
docs/
  decisions/     ADRs — one file per decision, numbered, with status
  spec/          the actual manifest/lockfile/PROPATH specification
  research/      background analysis that informed the decisions
src/main/kotlin/oepm/
  OepmPlugin.kt      Gradle plugin entrypoint — registers oepmInstall/oepmPropath/
                     oepmRegistryAdd tasks, the registries{}/registryRoot/projectRoot/
                     cacheDir DSL, supports -PoepmAdd=<package_name>[:<versionSpec>]
                     to add + resolve in one step
  manifest/          reads/writes oepm's manifest fields in openedge-project.json
                     (ManifestReader, BuildPathUpdater, DependenciesUpdater), the
                     DependencySpec union type (registry-routed vs. direct-source)
  registry/          Registry implementations: LocalDirectoryRegistry (local folder,
                     original v1), CatalogRegistry (remote git catalog),
                     PrefixRoutingRegistry (routes by configured prefix), PackageMatcher
                     (shared candidate-matching logic), RegistriesPropertiesFile
                     (oepm-registries.properties reading/appending)
  fetch/             git plumbing: GitCli (process wrapper), GitPackageFetcher (bare-clone
                     + git-worktree cache, manifest read, shared by CatalogRegistry and
                     direct-source deps)
  lock/              oepm.lock reading + integrity verification against it
  integrity/         content hashing for oepm.lock's integrity field
  propath/           turns a project's buildPath into an ordered, absolute PROPATH
  resolver/          resolves a dependency graph transitively, with version-conflict
                     and PROPATH-namespace-collision checks across the whole graph
  version/           SemVer + npm-style caret range matching
src/test/kotlin/oepm/           unit tests, mirrors src/main/kotlin/oepm layout
src/functionalTest/kotlin/oepm/ Gradle TestKit tests — apply the real plugin to a
                                 throwaway project and run its tasks for real, using
                                 this repo's own small fixture packages
                                 (src/functionalTest/resources/fixtures/)
scaffold/templates/    templates scaffoldProject renders into a new/existing project
                       (settings.gradle.kts, build.gradle.kts, gradle.properties,
                       openedge-project.json)
oepm / oepm.bat        per-project thin CLI wrapper, scaffolded into each project —
                       translates `oepm install <package>` / `oepm propath` /
                       `oepm registry add` into the equivalent ./gradlew calls;
                       auto-detects the .oepm/ vs. root-level layout; finds its
                       target project by its own file location, so it works
                       with zero global setup (CI, a fresh machine, etc.)
cli/oepm / cli/oepm.bat  the same CLI, but installed once (added to PATH) and
                       finds its target project by walking upward from your
                       current directory instead — see "Per-machine setup"
                       below for why these are two different scripts, not one
cli/install.sh / cli/install.ps1  one-time, idempotent setup - adds cli/ to
                       PATH; oepm-init offers to run this automatically
oepm-init / oepm-init.bat  interactive wrapper around the scaffoldProject task - see
                           "Per-machine setup" below
cli/oepm-init / cli/oepm-init.bat  thin forwarders to the real oepm-init above -
                           reachable once cli/ is on PATH, so oepm-init works
                           from any project too, not just via its full path
build.gradle.kts       plugin build config (Kotlin, Java 17 toolchain), plus the
                       scaffoldProject task itself
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
- Registry: a local filesystem directory, or a remote git catalog repo
  (see "Status" above) — no HTTP registry, no auth.
- Publishing: minimal — `maven-publish` to a plain git-repo-hosted Maven
  repository (no hosted registry service, no auth) — see
  [ADR-0008](docs/decisions/0008-plugin-publishing-v1.md). Registry/catalog
  publishing is still entirely manual (write the reference file/tag by
  hand) — no publish tooling yet.
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
2. **The consumer project's `settings.gradle.kts`** needs
   `pluginManagement { includeBuild("<path-to-your-clone-of-this-repo>") }`
   pointing at wherever *that person* cloned this repo — see
   [openedge-package-manager](https://github.com/erudys27/openedge-package-manager)'s
   `settings.gradle.kts` for the pattern. That path is a `gradle.properties`
   value (`oepmToolPath`), not hardcoded — override it per-clone instead of
   editing `settings.gradle.kts`. This only works within a team sharing
   consistent clone locations; it isn't viable for an arbitrary external user.

   Setting all of this up by hand is repetitive — **`oepm-init`** does it
   for you instead, interactively, from *your own project's* terminal
   (new or already-existing project, doesn't matter):
   ```
   cd my-project                          # your project - new or existing
   /path/to/oepm-tool/oepm-init           # oepm-init.bat on Windows
   ```
   (Once the global CLI below has been installed once, bare `oepm-init`
   works the same way from any directory — `cli/oepm-init`/`cli/oepm-init.bat`
   are thin forwarders to this same script, reachable via `PATH` without
   needing oepm-tool's own root on `PATH` too, which would reintroduce the
   footgun described below.)

   It prompts for however many registries you want to add, then wires
   everything up. For a genuinely fresh project, Gradle's own files
   (wrapper, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`)
   go into a `.oepm/` subfolder, so the project root only shows what's
   actually yours:
   ```
   my-project/
     .oepm/              Gradle wrapper, settings/build.gradle.kts, gradle.properties - never need touching
     openedge-project.json
     oepm-registries.properties
     oepm / oepm.bat
     src/
   ```
   `oepm`/`oepm.bat` auto-detect this layout (vs. Gradle files at the
   root, for an already-set-up project that predates this - e.g. this
   repo's own demo app) and route to the right one, so the same two
   scripts work either way with no migration needed for existing
   projects. Everything else about `oepm-init`: never overwrites
   `settings.gradle.kts`/`build.gradle.kts`/`gradle.properties` if they
   already exist (warns and tells you what to add by hand instead), and
   generates or **patches** `openedge-project.json` — if one already
   exists (e.g. from vscode-abl), only the missing oepm-specific fields
   (`package_name`, `dependencies`) are added; everything else is left
   untouched. `package_name` is auto-inferred from your project's real
   `.cls` files when possible (same namespace-scanning
   `oepm.manifest.PackageNameInferrer` already does for a package's own
   manifest); if it can't be inferred, `oepm-init` asks for it directly
   rather than failing. Safe to re-run — already-correct files are left
   alone.

   Under the hood this calls oepm-tool's own `scaffoldProject` Gradle
   task (a plain task in `build.gradle.kts`, not something the plugin
   itself registers — a not-yet-wired project has no build to run a task
   against yet), which can also be invoked directly:
   ```
   ./gradlew scaffoldProject -PtargetDir=<path> [-ProotProjectName=<name>] \
       [-PpackageName=<name>] [-Pregistries=<prefix1>=<url1>[,<prefix2>=<url2>,...]]
   ```

4. **(Optional) Install the global `oepm` CLI** so `oepm install`/
   `oepm propath`/`oepm registry add` work with no `./`/`.\` prefix, from
   inside *any* oepm-managed project — set up once, works for every
   current and future project, no per-project step needed:
   ```
   cli/install.sh      # cli\install.ps1 on Windows
   ```
   (`oepm-init` also offers to run this for you after scaffolding a
   project, so this is usually already done by the time you need it.)
   Both scripts add `cli/` to your `PATH` (idempotent — safe to re-run).
   Open a new terminal afterward — PATH changes don't apply to
   already-open sessions, including new tabs in an already-running
   terminal app; a genuinely new process is required. This is
   `cli/oepm`/`cli/oepm.bat`, **not**
   the per-project `oepm`/`oepm.bat` scaffolded into each project — those
   two look similar but work differently on purpose. The per-project
   scripts find their target by their own file location (so a project
   never needs anything installed globally — works out of the box, in CI,
   on a machine that's never seen `oepm-tool`). `cli/oepm` instead finds
   its target by walking *upward from your current directory* looking for
   `openedge-project.json` — the same way `git`/`npm` find their project
   root from any subfolder — so it's safe to have exactly one copy on
   `PATH` and have it correctly operate on whichever project you're
   actually standing in. (Putting a *per-project* `oepm`/`oepm.bat` on
   PATH instead is a real footgun: it would silently keep operating on
   wherever that one file lives, regardless of which project's directory
   you're actually in — this happened for real during development, see
   the git history/PR for `feature/global-oepm-cli`.)

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
wherever the plugin got published. `./gradlew publish` publishes the
current version to a local, disposable folder by default
(`-PoepmPublishRepoUrl=...` to target somewhere real).

## Remote registries

Registries come from two mergeable sources — a prefix declared in both is
a duplicate-prefix error, same as declaring it twice in one source.

**`oepm-registries.properties`** (project root) — the CLI-mutable one,
meant to be committed like any other project config, not gitignored:
```
ba.prefix=ba.
ba.catalogUrl=https://github.com/erudys27/registry-ba.git
```
Add to it with `oepm registry add <prefix> <url> [<name>]`, or just
`oepm registry add` with no arguments for an interactive prompt (`name`
defaults to the prefix without its trailing `.`) — this is what
`oepm-init`/`scaffoldProject` write to as well, and it's safe to
programmatically append to. Never hand-patched by oepm itself otherwise.

**`registries {}`** in `build.gradle.kts` — the hand-authored DSL,
richer/more explicit, for when you're editing the build script directly
anyway:
```kotlin
oepm {
    registries {
        create("ba") {
            prefix.set("ba.")
            catalogUrl.set("https://github.com/erudys27/registry-ba.git")
        }
    }
}
```

A dependency `"ba.calculator": "^1.0.0"` routes to whichever registry's
prefix it starts with. The registry itself holds no package content —
just a reference file (`packages/calculator/1.0.0.json`) pointing at the
package's own dedicated repo + tag; `oepmInstall` fetches that repo into a
tool-managed cache (`~/.oepm/cache` by default, override with
`-PoepmCacheDir=...`), same convention as `~/.m2`/`~/.npm` — nothing to
set up by hand. Each package gets one bare clone (no working-tree files,
just git history) plus one `git worktree` checkout per version actually
used, so re-resolving an already-seen version is a local operation and a
new version of an already-cached package only costs an incremental fetch,
not a full re-clone. See
[github.com/erudys27/openedge-package-manager](https://github.com/erudys27/openedge-package-manager)
for a real, working example (two registries, a transitive dependency, and
a direct-source dependency, all live).

Installed packages land in `oepm_packages/` nested by which registry
routed them, so two registries can each have their own same-named package
without colliding on disk — `"ba.calculator"` lands at
`oepm_packages/ba/calculator/src`, `"cw.logger"` at
`oepm_packages/cw/logger/src`. A direct-source dependency (no registry
involved) lands under `oepm_packages/_direct/<name>/src` instead. This is
purely a folder-layout convenience — it has no effect on how a package's
own namespace is resolved or checked for collisions (see the PROPATH
namespace-collision check above); two packages can still collide on their
real `package_name` even while living in different `oepm_packages/`
subfolders.

If neither source has any entries, `oepmInstall` falls back to the
original `LocalDirectoryRegistry` behavior via `registryRoot` — a plain
local folder, one subfolder per package.

## Getting started

See [openedge-package-manager](https://github.com/erudys27/openedge-package-manager)
for a real, working consumer app.

To verify the plugin in isolation instead (no dependency on any other repo):

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
- `package_name` uniqueness is enforced within one `LocalDirectoryRegistry`
  directory, and across the whole resolved graph regardless of registry
  (the PROPATH namespace-collision check) — but not proactively at
  publish time, only when two colliding packages actually get resolved
  together.
- `oepm.lock`'s `source` field is currently written as an absolute
  filesystem path (not portable across machines/checkouts) — needs a
  relative or `local:///`-style scheme instead, per
  [lockfile-format.md](docs/spec/lockfile-format.md).
- Circular-dependency and version-conflict errors from `oepm/resolver/`
  are raw exception messages surfaced through Gradle's build failure
  output — no dedicated, friendlier error reporting yet.
- No cache concurrency/locking for multiple builds sharing one cache dir
  (e.g. CI).
- Catalog/registry authoring is entirely manual — no publish tooling to
  generate reference files or bump versions.
