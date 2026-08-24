# Next steps

Where things stand: design docs, ADRs, and a working vertical slice are on
`develop` at
[github.com/erudys27/openedge-package-manager](https://github.com/erudys27/openedge-package-manager).
`oepmInstall`/`oepmPropath` do real work now — resolution against a local
registry (transitive: a resolved package's own dependencies are resolved
too, with a version-conflict check across the whole graph), source copy
into `oepm_packages/`, `buildPath` auto-update, `oepm.lock` writing, and a
one-step add-and-resolve mode (`-PoepmAdd=<package_name>`).
`demo/exploration/consumer-app` applies the plugin for real; see README's
"Getting started".

## 1. Implementation shape is settled

Kotlin, as a Gradle plugin reusing Gradle's dependency-resolution engine —
see
[docs/decisions/0001-implementation-language.md](docs/decisions/0001-implementation-language.md)
(accepted). Nothing left to decide here.

## 2. Resolve the other open questions

Version range syntax (caret ranges) and database dependency handling
(ADR-0003, rejected for v1) are now both decided — see README's "Scope for
v1". Remaining open questions are listed at the bottom of
[README.md](README.md) (`.pf` file output, PROPATH-shadow warning,
`oepm.lock`'s non-portable `source` path).

## 3. Vertical slice: done

`oepmInstall` resolves declared dependencies against a real
`LocalDirectoryRegistry` (matches `package_name` + a caret-range version
spec) **transitively**: `oepm/resolver/DependencyResolver` walks each
resolved package's own `openedge-project.json` dependencies too, until the
whole graph is flat, enforcing exactly one version per `package_name`
across the graph (a conflicting version requirement, or a circular
dependency, fails the build loudly instead of silently guessing). Every
resolved package (direct and transitive) gets its source copied into
`oepm_packages/<name>/src`, written to `oepm.lock`, and auto-added to
`buildPath` (`BuildPathUpdater`). `-PoepmAdd=<package_name>[:<versionSpec>]`
also writes the `dependencies` entry itself (`DependenciesUpdater`), so a
dependency can be added and resolved (including its own transitive
dependencies) in one command without hand-editing the manifest.
`oepmPropath` reads `buildPath`'s source entries and prints them as
absolute paths. A thin `oepm`/`oepm.bat` wrapper translates
`oepm install <package>` / `oepm propath` into the underlying Gradle
calls. `demo/exploration/consumer-app` now applies the plugin for real
(composite build via `includeBuild`, since `oepm` isn't published) — see
README's "Getting started"; it declares only `calculator-package`
directly, with `greeter-package` pulled in transitively, to exercise this
for real. `calculator-one-package`/`calculator-two-package`/
`double-calculator-package` are a dedicated version-conflict fixture (see
`demo/packages/README.md`) — installing `double-calculator-package`
demonstrates the failure. `oepmInstall` is all-or-nothing: `-PoepmAdd`'s
manifest write (the new `dependencies` entry) only happens *after*
resolution succeeds, same as the `oepm_packages`/`oepm.lock`/`buildPath`
writes — a failed install (version conflict or otherwise) leaves the
manifest exactly as it was, including when `-PoepmAdd` was the thing that
triggered the failure. Covered by unit tests (`oepm/version`,
`oepm/registry`, `oepm/manifest`, `oepm/resolver`) and a `functionalTest`
suite that runs the real plugin via Gradle TestKit against fixtures
copied from the demo packages, including a version-conflict failure case
and a case specifically asserting `-PoepmAdd` doesn't leave a stray
manifest entry on failure.

## 3a. Splitting into 3 repos (tool / registry / demo)

Minimal plugin publishing is now in place (`maven-publish`, a git-repo-
hosted Maven repo — see [ADR-0008](docs/decisions/0008-plugin-publishing-v1.md))
and the demo projects' plugin source + `registryRoot` are `gradle.properties`-
driven instead of hardcoded, so a 3-repo split is unblocked. See
[MULTI-REPO-SETUP-PLAN.md](MULTI-REPO-SETUP-PLAN.md) for the remaining
step: the actual `git subtree split` to extract `demo/packages/` and
`demo/customer-app/` into their own repos, and deciding the real
publish-target URL (currently defaults to a local, disposable folder).

Still to do:

- Wire up dependency resolution through Gradle's own resolver rather than
  the current hand-written version matcher/graph walker, per ADR-0001 —
  see README's "Known deviation from ADR-0001".
- Fix `oepm.lock`'s `source` field: currently an absolute filesystem
  path, not portable across machines/checkouts.
- Real integrity hashing (currently a placeholder string).
- Multi-version registry support (currently one version per package).
- `package_name` uniqueness across separate registries (enforced within a
  single registry directory already).
- Include-collision linting (the namespace-relative-includes rule from
  ADR-0007 — written down, nothing checks for it yet).
- Add ktlint (or detekt) for style/lint.
- Add a build/test workflow (GitHub Actions) so pushes get checked
  automatically.

## 4. Repo hygiene

- Add a LICENSE file (currently none).
- Consider branch protection on `main`/`develop` once collaborators are
  added.
- Move the repo into the `gitlab.baltic-amadeus.lt` group instead/as well,
  if that's still wanted, once you have the right group role — see
  [docs/decisions/0005-naming.md](docs/decisions/0005-naming.md) for the
  naming context if you do.
