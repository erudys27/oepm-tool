# Handover

A short map for picking this project up — what it is, the moving parts,
what to type, and what's built vs. not. Everything here links to the
fuller docs rather than repeating them; read this first, then follow the
links for depth.

## What this is

`oepm` is a Gradle plugin that adds dependency management to Progress
OpenEdge ABL projects — install packages from git-hosted registries,
resolve transitive dependencies, generate PROPATH, all driven by a
project's existing `openedge-project.json`. See README.md's "What this
is" / "Status" for the full pitch and what currently works end to end.

## The repos

This used to be one monorepo; it's since split into five real, separate
GitHub repos (all under `github.com/erudys27/`):

| Repo | What it is |
|---|---|
| **`oepm-tool`** (this repo) | The plugin itself — everything under `src/`, plus the CLI (`oepm`/`oepm.bat`, `cli/`), scaffolding (`scaffold/`, `oepm-init`), and docs. |
| **`openedge-package-manager`** | The demo/consumer app — a real project that uses oepm, showing two registries, a transitive dependency, and a direct-source dependency, all live. Good place to see the tool actually being used. |
| **`registry-ba`**, **`registry-cw`** | Catalog registries — small repos holding only reference files (`packages/<name>/<version>.json`) that point at a package's own dedicated repo + tag. No package content lives in a registry itself. |
| **`calculator`**, **`logger`**, **`greeter`** | Individual packages, each its own repo, each tagged per version. `calculator`/`logger` are referenced from the catalogs above; `greeter` is a direct-source dependency (no catalog entry at all). |

## One-time setup

1. Clone `oepm-tool`.
2. Run `./oepm-init` (`oepm-init.bat` on Windows) from inside whatever ABL
   project you want to wire up to oepm — prompts for registries, sets
   everything up, and offers to install the global CLI too. Full detail
   (including the non-interactive path) in README.md's "Per-machine
   setup".
3. If you skipped that prompt, `cli/install.sh` (`cli\install.ps1` on
   Windows) does the same PATH setup on its own — one-time, safe to
   re-run. Once it's run, bare `oepm-init` also works from any directory
   (a thin forwarder in `cli/`, not a copy — see README's "Per-machine
   setup" for why it's not simply oepm-tool's whole root added to PATH).

## Commands you'll actually type

```
oepm-init                              wire up a new/existing project (interactive)
oepm install                           resolve declared dependencies
oepm install <package>[:<versionSpec>] add + resolve a dependency in one step
oepm propath                           print the generated PROPATH
oepm propath --tests                   ...also including buildPath's "test" entries
oepm registry add [<prefix> <url>]     add a registry (interactive if omitted)
```

`oepm` here means whichever CLI applies — the per-project `./oepm`/`.\oepm.bat`
that never needs anything installed globally, or the global `oepm` (once
`cli/install` has run) that works from any project, any directory. See
README.md's "Per-machine setup" for why there are two and when each
applies — it matters, don't assume they're interchangeable by accident.

## What's built vs. decided-but-not-built

README.md's "Status" section is the authoritative, up-to-date list of
what actually works (multi-registry resolution, catalogs, direct-source
deps, integrity verification, PROPATH namespace-collision detection,
`oepm_packages/` nested by registry prefix, the `"test"` buildPath type,
the global CLI). A few things were explicitly discussed and decided
*against* building, for now — worth knowing so they don't get
re-litigated from scratch or assumed to be oversights:

- **Package namespace uniqueness is left as author convention, not
  enforced by the tool.** `package_name` is free text; nothing stops two
  authors from picking the same one. The real safety net is
  `DependencyResolver`'s namespace-collision check, which fails loudly
  *if* a project ends up depending on two packages that collide — but
  that's a per-project catch, not a global guarantee. See
  `docs/spec/manifest-schema.md`'s `package_name` row and open questions.
- **A `"resources"`/images `buildPath` type** (alongside `"source"`/`"test"`)
  was discussed and deliberately left out — no decided use case yet, and
  it needs its own call on whether/how it belongs on PROPATH at all. See
  `docs/spec/propath-generation.md`'s "buildPath entry types" section.

## Where to read more

- **`docs/spec/kotlin-gradle-files.md`** — a file-by-file tour of every
  `.kt`/`.kts` file in this repo, written for someone new to Gradle/Kotlin.
  Read this to understand the actual code.
- **`docs/spec/manifest-schema.md`**, **`lockfile-format.md`**,
  **`propath-generation.md`** — the design specs for `openedge-project.json`,
  `oepm.lock`, and PROPATH generation respectively.
- **`docs/decisions/`** — ADRs, one per real decision, numbered, with
  status. Read these when something in the code looks like an odd choice
  — it's very likely there's a reason written down here.
- **`docs/research/`** — background analysis (comparisons to npm/pip/Maven
  etc.) that informed the decisions above.

## One more thing

Some of this project's history exists as conversation context with an AI
assistant (design discussions, live-verification results, debugging
sessions), not as anything written into this repo. If something about
*why* a piece of code looks the way it does isn't answered by the docs
above, it may simply not be written down anywhere accessible — ask, or
treat the code + tests + docs here as the actual source of truth.
