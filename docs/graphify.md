# Graphify impact analysis

Epistola uses [Graphify](https://github.com/Graphify-Labs/graphify) as a local,
structural index for code-impact investigation. It can quickly suggest callers,
related definitions, and paths worth inspecting before an assistant opens broad
parts of the repository. It is a navigation aid, not an authority on architecture
or a substitute for source and tests.

## What is generated

The wrapper creates four production-only graphs:

| Scope        | Content                                                                  |
| ------------ | ------------------------------------------------------------------------ |
| `backend`    | Host applications, core, generation, web, REST API, crypto, and MCP code |
| `editor`     | Production editor TypeScript/JavaScript and icon sprite generation       |
| `support`    | Audit, quality, load-test, version-check, and commercial feature code    |
| `migrations` | Production Flyway SQL from the application and modules                   |

All generated data is local and ignored by both Git and AI context collection:

```text
graphify-out/
├── corpora/<scope>/             # scoped source mirror used for extraction
├── scoped/<scope>/graph.json    # query input
├── scoped/<scope>/graph.html    # interactive visualization
├── scoped/<scope>/GRAPH_REPORT.md
└── tooling/                     # local uv/Graphify cache
```

Open `graphify-out/scoped/<scope>/graph.html` in a browser to inspect a graph.
The full visualization can still be dense; the command-line queries are usually
more useful for impact work.

## Commands

`mise` is the only prerequisite. The wrapper pins uv and Graphify, installs them
into the ignored local tooling directory on first use, and sends no source to an
LLM. Extraction and clustering are code/SQL-only and local.

```bash
# Build a scope if missing, or incrementally refresh it when tracked or
# non-ignored untracked production source changes.
scripts/graphify.sh ensure backend

# Candidate impact around a symbol. Depth 1 is the default and best starting point.
scripts/graphify.sh affected backend SpringMediator 1

# Explain one symbol or find a structural path between two symbols.
scripts/graphify.sh explain editor EditorEngine
scripts/graphify.sh path support TenantBackupService TenantBackupStore

# Ask a bounded natural-language structural question.
scripts/graphify.sh query migrations "which tables refer to tenants"

# Inspect local graph state, force rebuilds, or measure context reduction.
scripts/graphify.sh status
scripts/graphify.sh build all
scripts/graphify.sh benchmark all

# Validate that supported production files are assigned to a scope.
scripts/graphify.sh verify-scopes
```

`ensure`, `affected`, `explain`, `path`, `query`, and `benchmark` compare a
content fingerprint before starting Graphify. When inputs have not changed they
reuse the graph immediately. When files changed, extraction is incremental and
also removes deleted files. `build` deliberately forces a full rebuild.

The CI workflow runs only `verify-scopes`; it neither downloads Graphify nor
commits generated artifacts. This keeps new supported production sources from
silently falling outside the index while avoiding a large binary-like graph in
version control.

## AI workflow

Use Graphify when an issue is cross-cutting, the code area is unfamiliar, or a
change asks for impact, callers, dependencies, or a route between symbols:

1. Choose the narrowest applicable scope and run `affected`, `explain`, or
   `path`. Start at depth 1; increase depth only for a specific reason.
2. Use the output to select candidate files and symbols, then open those source
   locations directly.
3. Confirm references with `rg`. Confirm cross-module boundaries in Gradle build
   files. Search tests separately because the graphs intentionally exclude test
   code.
4. Report verified impact as fact and label unverified graph relationships as
   candidates. If matching is ambiguous or noisy, discard the result and use
   direct source inspection.

Do not consult Graphify for a self-contained edit whose impact is already clear.
Do not infer runtime frequency, ownership, module coupling, correctness, or test
coverage from graph connectivity. Calls, imports, inheritance, shared framework
types, generated references, and SQL relationships naturally produce a large
connected component even when module boundaries are sound. Decoupling is better
evaluated from dependency direction, public interfaces, ownership, and the ease
of changing a module independently.

Graphify's language support is not equally precise for every construct. Dynamic
dispatch, dependency injection, reflection, framework wiring, string-based
routes, templating, generated code, and cross-scope relationships may be absent
or approximate. A short or missing path is therefore not proof that no impact
exists, and a returned path is not proof of a runtime call chain.

## Current baseline

On 2 August 2026, the four scopes contained 1,091 production files and produced
the following local graphs. Graphify's benchmark uses generic questions and a
naive token estimate, so these figures are directional rather than a performance
guarantee:

| Scope        | Files | Nodes |  Edges | Estimated average context reduction |
| ------------ | ----: | ----: | -----: | ----------------------------------: |
| `backend`    |   721 | 4,993 | 10,670 |                               23.0x |
| `editor`     |   173 | 2,117 |  5,233 |                                7.1x |
| `support`    |   156 | 1,310 |  2,061 |                               28.7x |
| `migrations` |    41 |   112 |    167 |                                2.9x |

The practical benefit is faster candidate selection, especially for backend and
support questions. It does not reduce the verification required before changing
code.

## Maintenance

- Add or adjust mappings in `scripts/graphify.sh` when production source moves
  or a supported language is introduced. `verify-scopes` will flag unassigned
  production candidates in CI.
- Bump `SCOPE_SCHEMA_VERSION` when scope semantics change so existing local
  graphs rebuild instead of being incrementally reused.
- uv and Graphify versions are pinned in the wrapper. Renovate proposes grouped,
  non-automerge upgrades. Review release notes, force-build all scopes, repeat
  representative `affected`/`explain`/`path` checks, and update this baseline
  before accepting an upgrade.
- Never commit `graphify-out/`. Delete that directory if a completely clean
  local rebuild is needed; the next command will recreate it.
