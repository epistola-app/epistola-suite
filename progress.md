# Epistola 2.0 — progress

What we touched, how each change is covered, and what we noticed but left alone, so that at any
moment we can see where 2.0 stands and check that nothing is uncovered.

The design lives in the contract repo (`epistola-contract`, branch `feature/v2`, ADRs
`docs/adr/0001`–`0005`). The suite plan is `docs/catalog-release-model-v2.md`, still to be rewritten
against those ADRs.

**Coverage legend:** ✅ test exists and was run green · 🟡 test exists, not run since the change ·
❌ no test · ➖ no test needed (docs, deletion, merge).

## Current state (16 October 2026)

- `release/2.0` = main up to v1.5.0 + #1003, #1056, #1057, #1060, #1061, #1088. Version
  `1.5.1-SNAPSHOT`.
- **CI on `release/2.0` is red by design**: contract `2.0.0-SNAPSHOT` exists only in the local Maven
  repository, not on Sonatype snapshots.
- Active branch `feat/contract-v2-catchup` (worktree `.claude/worktrees/contract-v2-catchup`, not
  pushed): catching up with the contract snapshot of 16 October.

## Touched

### `feat/contract-v2-catchup` (in progress, not pushed)

| Change                                                                                                                                                                                   | Where                                               | Coverage                                                                                      |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| Key renames (`slug`→`key`, `themeId`→`themeKey`, `iconAssetSlug`/`imageAssetSlugs`→`iconImageKey`/`imageKeys`, `AttributeAssignment.catalog`→`catalogKey`), compiler-reported sites only | 53 files, `c31b75793`                               | 🟡 existing catalog import/export tests; core tests do not compile yet                        |
| `FontRefs.parse` reads `key`, falls back to `slug` for stored content                                                                                                                    | `modules/generation/.../FontRefs.kt`                | ❌ no test for the `slug` fallback                                                            |
| `ImportStencil` without version: skip if equal to latest published, else publish next                                                                                                    | `catalog/commands/ImportStencil.kt`                 | ❌ needs a test (replaces `StencilVersionImportConflictTest`)                                 |
| Import-time stencil-version conflict scan removed                                                                                                                                        | `catalog/commands/ImportCatalogZip.kt`              | ➖ deletion; `StencilVersionImportConflictTest` obsolete, still to delete                     |
| Stencil version-pin rule moved from the contract validator into the suite                                                                                                                | `templates/validation/TemplateDocumentValidator.kt` | 🟡 `PublishVersionTest`, `StencilIntegrationTest` ("cannot reference draft stencil versions") |

**Stencils by reference (step 2, started):**

| Change                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Where                                                                                                                                   | Coverage                                                                                                                           |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `StencilInstances.collapse` / `expand`: stored form = reference + bindings + one placeholder per non-empty fill (IDs derived from instance + name); expand inlines with derived IDs, recursive for stencils in stencils (inner refs resolve in the stencil's catalog), recursion and depth guards, findings `STENCIL_NOT_FOUND`, `RECURSION`, `DEPTH_EXCEEDED`, `UNKNOWN_PLACEHOLDER`, `UNBOUND_REQUIRED_PARAMETER` (blocking), `UNKNOWN_PARAMETER` (warning) | `stencils/model/StencilInstances.kt`                                                                                                    | ✅ `StencilInstancesTest` (15, `:modules:epistola-core:unitTest`)                                                                  |
| Lexical fill scope: a fill reads the scope its instance sits in; stencil content and defaults read `params`; nested fills chain; `legacyFillScope` keeps pre-2.0 behaviour per instance                                                                                                                                                                                                                                                                       | `generation/pdf/RenderContext.kt` (`ExpressionScope`, `fillScope`), `StencilNodeRenderer`, `PlaceholderNodeRenderer`, `StencilNodeKeys` | ✅ `StencilFillScopeRenderTest` (7; the two scope-distinguishing tests fail without the change); all 672 generation tests green    |
| Stencil isolation: content sees only its params + `sys` unless the instance carries `readsTemplateData` (set by expand from the stencil, dropped by collapse)                                                                                                                                                                                                                                                                                                 | `StencilNodeRenderer`, `ParameterScope.aliasOf`, `StencilInstances`                                                                     | ✅ 4 new `StencilFillScopeRenderTest` cases (two fail without isolation), generation `--rerun` green; ✅ `StencilInstancesTest` 16 |
| Obsolete `StencilVersionImportConflictTest` deleted; `CatalogExportImportTest` imports without version                                                                                                                                                                                                                                                                                                                                                        | core tests                                                                                                                              | ➖ / 🟡                                                                                                                            |

Not wired in yet: nothing calls `StencilInstances` so far.

Build state: generation, epistola-core (main), quality, testing, MCP compile. epistola-core tests
(7 errors) and rest-api (213) do not.

**Blocker:** wire v8 strips stencil version pins from template content, but the suite still needs
pins to publish a template, so importing a catalog with stencil instances fails. Decision pending:
a pin-filling stopgap, or remove stencil versions now (recommended).

### Merged into `release/2.0` (15 October)

| Change                                                                                                       | PR / commit              | Coverage                                                                                                              |
| ------------------------------------------------------------------------------------------------------------ | ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| REST on contract 2.0 (state of 2 October)                                                                    | #1061                    | ✅ `./gradlew test` 3966 green locally; ❌ CI red by design                                                           |
| — merge of release/2.0: conflicts in `ReleasePreviewResolver`, `DocumentDtoMappers`, `EpistolaTemplateApiIT` | `bf7e0f0b1`              | ✅ same run                                                                                                           |
| — JavaScript-expression rejection test ported from the draft endpoint to `/content`                          | `EpistolaTemplateApiIT`  | ✅                                                                                                                    |
| — `ListReleaseDependencies` deleted; a release returns `dependencies: []`                                    | `EpistolaCatalogApi`     | ✅ `ReleasesAndDeploymentsApiIT` asserts empty                                                                        |
| Environments deploy a catalog release                                                                        | #1060                    | ✅ CI green                                                                                                           |
| — compare-trigger UI test removed with the template deployments tab                                          | `TemplateSettingsUiTest` | ➖                                                                                                                    |
| Generation renders releases only; `release_dependencies` dropped                                             | #1057                    | ✅ CI green                                                                                                           |
| Main (v1.5.0) merged in; `FontFaceBytes` semantic conflict (#1104)                                           | `3ea58d82a`              | ✅ `test` 4054 green, `uiTest` green; `ReleaseRenderSourceTest` now asserts the face hash is the SHA-256 of its bytes |
| Version kept `1.5.1-SNAPSHOT` instead of `1.5.0`                                                             | `3ea58d82a`              | ➖                                                                                                                    |
| ADR 0029 closed as superseded                                                                                | #1058                    | ➖                                                                                                                    |

## Design: stencils by reference (step 2) — decided 16 October

Recorded as **contract ADR 0006** (`epistola-contract` `docs/adr/0006-stencil-instances-by-reference.md`,
commit `9ff4d72` on `feature/v2`, local), status Proposed: parts 1–4 decided, part 5 (isolation) proposed.

Today (mapped 16 October): a stencil instance is an **embedded copy** in the template's stencil node
(`children` slot), with props `stencilId`, `catalogKey`, `version`, `draftVersion`,
`parameterSchemaSnapshot`, `parameterBindings`; placeholder fills live in the copy's `fill` slots.
The renderer only reads the copy (`StencilNodeRenderer`), never the stencil. Upgrading an instance
is `UpdateStencilInTemplate` + `StencilContentReplacer`, which reports `droppedFills`,
`droppedBindings`, `unboundRequired` but blocks on none.

**Decided (16 October, user): no copy.** A template stores an instance as a reference (stencil key,
catalog when not its own), its bindings, and one placeholder node per placeholder it fills. The
stencil is resolved when the template is opened, previewed or rendered — in the same catalog
version, or in the pinned release of a dependency. A stencil change reaches every template at once;
releasing checks every instance against its stencil (unknown fill or unbound required parameter =
error, unknown binding = warning), like a compile step. No digests, no held back, no propagation.
Stencils in stencils work the same way (inner references resolve in the stencil's catalog; the outer
stencil fills the inner one's placeholders and can pass its own placeholders through); the suite
still refuses nested stencils (`rejectUnsupportedNestedStencilAuthoring`); **decided 16 October: stencils in stencils must be possible** — remove the refusal and verify the editor in the wiring step.

**Fill scope: lexical (decided 16 October, user).** A fill sees what was in scope where it was
written; a stencil's interface is exactly its placeholders and parameters.

Upgrade from 1.x must not change what renders (user requirement). Plan, not built:

- For every fill expression (same site walk as `JavaScriptExpressionScanner`) that reads the
  instance's alias (`paramsAlias`, default `params`) as `<alias>.<param>`, parse it (dashjoin
  JSONata AST) and substitute: a bound parameter by `(<binding>)` — the binding is evaluated where
  the instance sits, which is exactly the fill's new scope — and an unbound one with a default by
  that default as a JSONata literal.
- Not rewritten, instance marked `legacyFillScope: true` instead: the bare alias or dynamic access
  (`params[...]`), an optional unbound parameter without default (absent, not null), a binding that
  uses loop variables while the reference sits inside a loop of the fill (capture), a parse failure.
- A readiness check in 1.x lists the fills that read a stencil alias, split into rewritable and
  legacy; the 2.0 release check warns on every `legacyFillScope` instance.

**Decided 16 October (user):**

- 1.x instances on an older stencil version are **upgraded automatically** to the latest stencil
  (they render the latest after the upgrade). What no longer fits (dropped fills, unbound required
  parameters — the `StencilInstances` findings) is **reported**: upgrade report and quality findings.
- Copies that are not a normal locked copy (no `stencilId`, or content differing from the pinned
  version beyond fills) become plain content and are reported — collapsing them would lose content.
- A release preview of templates affected by a stencil change comes **later**; prepare for it by
  keeping "templates affected by a stencil change" a query of its own.

**Superseded proposal** (copies + held back; kept for the record):

- A stencil has one working copy (content + parameter schema) and a ready mark; `stencil_versions` goes.
- An instance keeps its embedded copy and loses `version`/`draftVersion`.
- **Marking a stencil ready moves every instance in the same catalog** whose template it does not
  break (`StencilContentReplacer`; breaking = it would drop a fill or binding, or leave a newly
  required parameter unbound). Breaking instances are **held back** on their old copy, listed in
  stencil usage, and block the release until the variant is edited.
- Cross-catalog instances move when the dependency pin moves (to confirm).
- Upgrade from 1.x: the first release is cut from today's copies (renders exactly as before); then
  instances behind the latest stencil are moved in the draft, breaking ones held back.

Goes with it (step 2 scope): `stencil_versions` and its commands (`CreateStencilVersion`,
`UpdateStencilDraft`, `PublishStencilVersion`, `ArchiveStencilVersion`), `PublishVersion`'s
published-stencil check, the interim `stencilVersionPinFindings`, `MultipleStencilVersionsInUseException`

- `FindStencilVersionExportConflicts`, `OnStencilConflict` and the renumber plumbing, the usage
  page's `UpgradeBlockReason`, the client-side bulk upgrade (`stencil-detail.js`), MCP
  `list_stencil_versions`/`get_stencil_version`/`update_stencil_draft`, editor `draft-hydration.ts`
  and `upgrade-refs.ts`, REST's draft/publish-based stencil working copy.

Decided (16 October, user):

- An instance stores a **content digest** and an **interface digest** (placeholders + parameter
  schema). Different content digest = behind. Same interface digest = safe to move. Different
  interface digest = check the instance (does it use a removed or retyped fill or binding, or leave
  a newly required parameter unbound) — only then held back.
- Cross-catalog instances move when the dependency pin moves, "like upgrading a Java library":
  preview the breaking instances before moving the pin; breaking ones are held back and block the
  release.
- While template versions still exist, a stencil update writes into template drafts.

Proposed, awaiting the user — what the template author sees on stencil ready:
A silent update · B update and reset templates to modified · C mark stale, update on request ·
**D (recommended)**: compatible copies move, template keeps its ready mark with a non-blocking
"updated by stencil X" note in history and release changes; breaking ones held back; the stencil
author sees a preview ("updates 37 templates, 2 held back") when marking ready. C fits only if
template and stencil owners must not change each other's work.

Open questions: see "Open decisions".

## Noticed, not touched

| What                                                                                                                                                                                                                                                                                           | Where                                                                                                                                                | Why it matters                                                                                                                     | When                                                                     |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| **Integration tests blocked**: every v7 bundled/test catalog import fails — wire v8 needs a registry and namespace for pre-v8 archives (`CatalogMigrationOptions`), the suite passes none. 137 of 223 core stencil/render tests fail with `CatalogSchemaUnknownException: … names no registry` | `catalog/migrations/CatalogSchemaMigrator.kt`                                                                                                        | nothing above unit level can be verified until this works                                                                          | identity step (needs decisions)                                          |
| Contract still describes copies: v8 validator requires template placeholders under a stencil node (fine for the stored form), `markStencilReady`/`listStencilUsage`/catalog version carry `heldBack`, stencil node prop is `stencilKey` (suite stores `stencilId`)                             | contract `feature/v2`                                                                                                                                | contract change to write up for the user                                                                                           | before REST step                                                         |
| ~~Fills are scoped dynamically~~ — fixed in `1876f4539`; a template's fill saw the stencil's `params` (`StencilNodeRenderer` + `ParameterScope` push, `PlaceholderNodeRenderer` renders the fill in the stencil's context)                                                                     | `modules/generation/.../pdf`                                                                                                                         | a stencil parameter rename silently breaks fill expressions; a stencil in a placeholder binds against the outer stencil's `params` | decide scope; then readiness check for fills referencing a stencil alias |
| The editor offers the stencil's `params` in a fill's expression autocomplete/preview (scope provider) — must follow the lexical rule                                                                                                                                                           | `modules/editor` (stencil/placeholder scope provider)                                                                                                | editor would suggest variables that render empty                                                                                   | with the editor step                                                     |
| Nested stencils are refused by the suite though the model and contract allow them                                                                                                                                                                                                              | `TemplateDocumentValidator.rejectUnsupportedNestedStencilAuthoring`                                                                                  | product decision                                                                                                                   | later                                                                    |
| 1.x stencil nodes without `stencilId` (inline/detached content) and copies that differ from their pinned version (written via REST/MCP, never compared) — collapsing would lose content                                                                                                        | template content in 1.x databases                                                                                                                    | migration must turn them into plain content and report them                                                                        | storage migration                                                        |
| A structurally compatible stencil change can still break layout in every template (e.g. a taller letterhead pushes content to page 2)                                                                                                                                                          | release flow                                                                                                                                         | needs a release preview of affected templates, ideally before/after renders of data examples                                       | release step (proposed as a requirement)                                 |
| Lexical scoping removes "scoped slots": a stencil cannot hand loop `item` values to a fill                                                                                                                                                                                                     | model                                                                                                                                                | add an explicit, declared placeholder exposure later if 1.x data shows the pattern                                                 | after readiness data                                                     |
| Expanded node IDs contain `:` (`inst:node`) — editor/ProseMirror acceptance unverified                                                                                                                                                                                                         | `StencilInstances.inline`                                                                                                                            | possible editor breakage                                                                                                           | wiring step                                                              |
| 1.x instances pinned to an older stencil version will render the latest after upgrade                                                                                                                                                                                                          | migration                                                                                                                                            | behaviour change; option to convert them to plain content instead                                                                  | decide default before the migration                                      |
| Stencil content sees the whole template data and loop variables (renderer adds `params`, keeps `data`)                                                                                                                                                                                         | `generation/pdf/ParameterScope.kt`                                                                                                                   | stencils silently depend on each template's data model                                                                             | ADR 0006 part 5                                                          |
| `release_dependencies` must come back: the contract pins dependency releases (`releaseVersion` + `releaseFingerprint`)                                                                                                                                                                         | #1057 dropped it                                                                                                                                     | releases render with pinned dependencies                                                                                           | step 6                                                                   |
| `environment_catalog_deployments` PK is (env, catalog): one release per environment                                                                                                                                                                                                            | `V20261001210623` (unreleased)                                                                                                                       | contract: several releases per environment                                                                                         | step 7                                                                   |
| Version-comparison dialog is orphaned: no page includes it since the deployments tab went                                                                                                                                                                                                      | `fragments/version-comparison.html`, `templates/version-comparison.html`, compare route in `DocumentTemplateRoutes.kt`, hook in `template-detail.js` | dead UI and route                                                                                                                  | with version removal                                                     |
| `stencilRenumbers` plumbing is dead (nothing fills it for v8)                                                                                                                                                                                                                                  | `ImportCatalogZip`, `ImportTemplates` (8 sites)                                                                                                      | dead code                                                                                                                          | with stencil version removal                                             |
| `ExportResources` still selects `sv.id AS version`                                                                                                                                                                                                                                             | `catalog/queries/ExportResources.kt`                                                                                                                 | unused column                                                                                                                      | with stencil version removal                                             |
| Import ignores the v8 data-example `key`, assigns a random UUID                                                                                                                                                                                                                                | `ImportCatalogZip.kt:698`, `InstallFromCatalog.kt:267`                                                                                               | data-example keys must round-trip (`a30bfd6`)                                                                                      | step 5                                                                   |
| `FontRef.namespace`/`registry` ignored by parser and resolver                                                                                                                                                                                                                                  | `FontRefs.kt`, `FontFamilyResolver`                                                                                                                  | cross-registry font references                                                                                                     | step 4                                                                   |
| MCP `CatalogInfo` DTO keeps `iconAssetSlug`/`imageAssetSlugs` names                                                                                                                                                                                                                            | `epistola-mcp/.../CatalogInfo.kt`                                                                                                                    | naming drift from the contract                                                                                                     | step 8                                                                   |
| Unresolved-font quality finding writes context key `slug`                                                                                                                                                                                                                                      | `UnresolvedFontQualitySource.kt`                                                                                                                     | naming; changing it changes fingerprints                                                                                           | decide in step 8                                                         |
| #1061 stopgaps: mark ready publishes the draft, status derived from `template_versions`                                                                                                                                                                                                        | `ListCatalogDrafts`, `WorkingCopyStatus`                                                                                                             | replaced by catalog versions                                                                                                       | step 3                                                                   |
| MCP draft tools still on template versions (incl. #1102)                                                                                                                                                                                                                                       | `epistola-mcp`                                                                                                                                       | follow the version model                                                                                                           | step 8                                                                   |
| Suite keys may violate the new key rule (3–50 chars)                                                                                                                                                                                                                                           | all resource tables                                                                                                                                  | needs a migration with the contract's pad/truncate rule; not measured                                                              | step 4                                                                   |
| `docs/catalog-release-model-v2.md` outdated (D14 pins, no-pins, ready mark dropped)                                                                                                                                                                                                            | docs                                                                                                                                                 | plan of record is wrong                                                                                                            | step 0                                                                   |
| `ConsumersApi` not implemented                                                                                                                                                                                                                                                                 | rest-api                                                                                                                                             | pre-existing since 1.x                                                                                                             | not 2.0                                                                  |
| ADR 0003 open question: undeploying a release another deployed release depends on                                                                                                                                                                                                              | contract                                                                                                                                             | refuse symmetrically?                                                                                                              | step 7                                                                   |
| ADR 0001 rule missing: a release depending on a hotfix cannot be published                                                                                                                                                                                                                     | contract                                                                                                                                             | noted in ADR 0003                                                                                                                  | after 2.0.0                                                              |
| Merged remote branches not deleted: `feat/render-from-release`, `feat/environment-deployments`, `feat/contract-2`                                                                                                                                                                              | GitHub                                                                                                                                               | housekeeping                                                                                                                       | any time                                                                 |
| Main's open #1107 sets `1.5.1-SNAPSHOT`; release/2.0 already has it                                                                                                                                                                                                                            | `gradle.properties`                                                                                                                                  | next main merge should be clean                                                                                                    | next main merge                                                          |
| `ExpressionClipboardPlaywrightUiTest` fails on this machine only, passes on CI                                                                                                                                                                                                                 | UI tests                                                                                                                                             | local noise                                                                                                                        | —                                                                        |
| Dev DB needs a reset: `V20261001154558` changed in place                                                                                                                                                                                                                                       | local                                                                                                                                                | Flyway checksum                                                                                                                    | —                                                                        |

## Local branches not pushed

- `docs/v2-simplify-first` (`d5d362985`): "simplify first" note; partly superseded (drops the ready
  mark, one working copy per resource). Starting point for the plan rewrite.
- `fix/upstream-check-not-due-flake` (`4009c7952`, off main): fixes flaky `ExchangeUpstreamCheckTest`;
  wants a PR into main with `no-changelog`.

## Contract `feature/v2` — what the suite has to follow

| Commit / ADR                              | What                                                                                                                     | Suite status        |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ | ------------------- |
| ADR 0002 (accepted), `0f67e33`, `6891e5e` | Catalog versions, draft or released; several drafts; independent copies, nothing merged; `behind` + `acknowledgedBehind` | not started         |
| ADR 0005 (proposed)                       | Pull-based merge of versions                                                                                             | after 2.0.0         |
| ADR 0003 (accepted), `6467581`            | Identity = registry + namespace + key; `~` = default registry; mirrors are config                                        | not started         |
| `17852a3`                                 | `v5:` fingerprints; `deployRelease` checks dependency releases, `includeDependencies`                                    | not started         |
| `fb6fb9f`, ADR 0003                       | Declared dependencies with selector, pinned `releaseVersion` + `releaseFingerprint`                                      | not started         |
| ADR 0004 (accepted), `90e368b`            | `slug` → `key`; key unique per type; canonical name                                                                      | renames in progress |
| `f05f749`                                 | One key rule; v7→v8 pads/truncates keys                                                                                  | not started         |
| `a30bfd6`                                 | Relative archive paths, data-example keys, variant metadata, template description                                        | not started         |
| `27e37c3`                                 | `cellStyles`, `columnStyles`, `minHeight`, `verticalAlign`; unknown style keys warn                                      | not started         |
| ADR 0001 (proposed)                       | Hotfix releases `X.Y.Z+hotfix.N`                                                                                         | after 2.0.0         |

## Plan

0. Contract snapshot where CI can fetch it; rewrite the suite plan against ADRs 0001–0005.
1. Rename pass (in progress).
2. Remove versions from resources (stencils first); keep the ready mark.
3. Catalog versions: draft/released, several drafts, `behind`, renumber, delete; upgrade rule: last
   published → first release, draft → open draft, older versions lost.
4. Identity: registry + namespace + key; configuration; key-rule migration.
5. Wire v8 import/export; bundled catalogs regenerated.
6. Dependencies: declared per version, pinned at release, release-time checks.
7. Deployments: several releases per environment, dependency check, selection by `version` +
   environment.
8. REST to the spec; UI; MCP.
9. Upgrade job on a 1.x-shaped database; cleanup; docs; demo catalog; ADR 0028.

## Open decisions

- Registry and namespace for the bundled system and demo catalogs (e.g. `exchange.epistola.app` / `epistola`)?

- Keep `paramsAlias` now that lexical scope makes it unnecessary for nesting?

- Stencils: inserting a stencil that was never marked ready — allowed, from its working copy?

- Stencil pins on import: stopgap, or remove stencil versions now?
- Catalog identity in the schema: registry/namespace in every key, or a surrogate key?
- Default registry when none is configured (the installation's host?).
- Publish contract snapshots to Sonatype so `release/2.0` CI runs?
- Where `progress.md` lives once the catch-up branch merges (committed, or kept local).

## Notes

- Run Gradle with `--no-watch-fs`; worktrees need `pnpm install --frozen-lockfile && pnpm build`.
- Don't trust a background command's exit code; check for `BUILD SUCCESSFUL`/`FAILED` and the
  test-result XML timestamps.
