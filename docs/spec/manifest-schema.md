# Manifest schema

Status: decided (2026-08-13) — single-file design: oepm-specific keys are
added directly to `openedge-project.json` rather than a separate
`oepm.json`. Exact key names/types below are still draft and may change
once the vertical slice is actually built against them. Depends on
[ADR-0002](../decisions/0002-oo-abl-only-v1.md) (OO ABL only) and
[ADR-0004](../decisions/0004-source-as-primary-artifact.md) (source as
primary artifact) staying as accepted.

## File

`openedge-project.json` — the vscode-abl ecosystem's existing per-project
config file, already carrying `name`, `version`, `oeversion`, and
`buildPath`. One per package, at the root of the package's directory tree
(the directory that itself represents the top of the OO ABL package
namespace, per [ADR-0002](../decisions/0002-oo-abl-only-v1.md)).

## Why one file instead of a separate oepm.json

A prior draft of this spec kept oepm's manifest in its own `oepm.json` to
avoid coupling early iteration to a file format owned by another project.
That's no longer the plan: one file is preferable to two as long as it
works correctly (no field collisions), and prototyping shows it can.

Two of `openedge-project.json`'s existing fields were checked for
collision before deciding this:

- **`name`** — the extension's existing `name` is a free-form project
  label (e.g. `"calculator-package"`), but oepm requires this value to
  exactly equal the OO ABL package namespace (e.g. `"example.calculator"`)
  for dependency resolution to work (a consumer's `dependencies` map is
  keyed by this value). These are genuinely different constraints on the
  same field name, so oepm does **not** reuse `name` — it adds a separate
  `package_name` key instead, leaving the extension's `name` untouched.
- **`version`** — no equivalent conflict found; both the extension and
  oepm mean "this package's version." Reused as-is, one field, no new key.

## Fields

| Field | Owner | Notes |
|---|---|---|
| `name` | vscode-abl extension (existing) | Free-form project label. Not read by oepm — do not assume it matches the namespace. |
| `version` | shared (existing) | Semver. Reused directly by oepm; no separate version field. |
| `oeversion` | vscode-abl extension (existing) | Target OpenEdge version. May end up doing double duty for an oepm min-version check — not confirmed, see open questions. |
| `buildPath` | vscode-abl extension (existing) | Ordered list of source roots. oepm's generated PROPATH entries for resolved dependencies are added into this same list — see [propath-generation.md](propath-generation.md) for the open question on ordering and on warning when a generated entry would shadow one already present here. `buildPath[0]` (when it's the package's own source) also serves as `package_root` — see below, no separate field. |
| `package_name` | oepm (new) | The OO ABL package namespace this manifest describes (e.g. `example.calculator`). No leading period-containing directory components (language constraint). Cannot start with `Progress` (reserved). Uniqueness within a single registry directory is enforced at resolve time (`LocalDirectoryRegistry.findAny` fails loudly if two folders declare the same `package_name`) — not enforced across separate registries. Independent of whatever key a *dependent* package uses to reference this one (see `dependencies` below) — a package's own `package_name` never needs a registry's routing prefix baked in. |
| `dependencies` | oepm (new) | Map of dependency key → spec, one of two shapes (decided 2026-08-25, see `oepm.manifest.DependencySpec`): <br>**Registry** — a plain caret-range string (`"ba.calculator": "^1.0.0"`, unchanged since 2026-08-13). The key must already be a fully-qualified, registry-routed name (prefix included) — `oepm.registry.PrefixRoutingRegistry` can't route an unprefixed name. <br>**DirectSource** — `{ "repoUrl": "...", "ref": "v1.0.1" }` (new). Fetched by a plain git clone, no registry lookup at all — lets a package depend on another without either side needing a catalog entry. The key doesn't need to be registry-qualified; when this is a *transitive* dependency (declared inside another package's own manifest, not the top-level consumer's), `oepm.resolver.DependencyResolver` inherits the enclosing package's own resolved prefix onto it (a direct-source `"greeter"` declared by a package resolved as `ba.calculator` becomes `ba.greeter` in the resolved graph / `oepm_packages/`, without `greeter` ever being routed). A root-level direct-source dependency has no parent to inherit a prefix from and keeps its bare declared key. |

`db` was previously sketched as a field here (`none`/`required`) but is
dropped entirely — [ADR-0003](../decisions/0003-db-deps-declared-not-managed.md)
(the ADR that would have justified it) is rejected for v1. DB-aware
packages are out of scope, not merely unmanaged, so there is nothing for
this field to flag.

`abl.min_oe_version` from the earlier draft is also dropped for now,
pending confirmation of whether it would just duplicate the existing
`oeversion` field (see open questions).

`package_root` from the earlier draft is dropped as a separate field
(decided 2026-08-13) — it's derived from `buildPath[0].path` instead,
since every example built so far has them identical, and a derived value
can't drift out of sync with `buildPath` the way a second stored field
could.

## Example

```json
{
  "name": "consumer-app",
  "version": "1.0.0",
  "oeversion": "12.8",
  "package_name": "example.consumer",
  "dependencies": {
    "example.calculator": "^1.0.0"
  },
  "buildPath": [
    { "type": "source", "path": "src" },
    { "type": "source", "path": "oepm_packages/example.calculator/src" }
  ]
}
```

## Open questions

- Whether to support a `devDependencies`-equivalent (e.g. ABLUnit test
  packages) separately from runtime dependencies.
- Whether `oeversion` can double as the min-OE-version check the earlier
  `abl.min_oe_version` draft field was for, rather than adding a new field.
- Whether scope stays OO-ABL-only ([ADR-0002](../decisions/0002-oo-abl-only-v1.md))
  — if that changes, `package_name`'s namespace-directory semantics here
  would need rework, not just a footnote.
- Whether source is really the right primary resolved artifact
  ([ADR-0004](../decisions/0004-source-as-primary-artifact.md)) — affects
  whether this manifest ever needs a compiled-artifact-adjacent field.
- `package_name` uniqueness is now enforced within a single registry
  directory: `LocalDirectoryRegistry.findAny` fails loudly if two folders
  both declare the same `package_name`, rather than silently picking
  whichever one the filesystem happens to list first. Still open: this
  only catches the collision within one `registryRoot` — nothing stops
  two *separate* registries (or a registry and a hand-copied
  `oepm_packages/` entry) from independently claiming the same
  `package_name`.
