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

## Design in progress: stencils without versions (step 2)

Today (mapped 16 October): a stencil instance is an **embedded copy** in the template's stencil node
(`children` slot), with props `stencilId`, `catalogKey`, `version`, `draftVersion`,
`parameterSchemaSnapshot`, `parameterBindings`; placeholder fills live in the copy's `fill` slots.
The renderer only reads the copy (`StencilNodeRenderer`), never the stencil. Upgrading an instance
is `UpdateStencilInTemplate` + `StencilContentReplacer`, which reports `droppedFills`,
`droppedBindings`, `unboundRequired` but blocks on none.

Proposal (contract `markStencilReady` / `heldBack` semantics):

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

Open questions: see "Open decisions".

## Noticed, not touched

| What                                                                                                                   | Where                                                                                                                                                | Why it matters                                                        | When                         |
| ---------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------------------- |
| `release_dependencies` must come back: the contract pins dependency releases (`releaseVersion` + `releaseFingerprint`) | #1057 dropped it                                                                                                                                     | releases render with pinned dependencies                              | step 6                       |
| `environment_catalog_deployments` PK is (env, catalog): one release per environment                                    | `V20261001210623` (unreleased)                                                                                                                       | contract: several releases per environment                            | step 7                       |
| Version-comparison dialog is orphaned: no page includes it since the deployments tab went                              | `fragments/version-comparison.html`, `templates/version-comparison.html`, compare route in `DocumentTemplateRoutes.kt`, hook in `template-detail.js` | dead UI and route                                                     | with version removal         |
| `stencilRenumbers` plumbing is dead (nothing fills it for v8)                                                          | `ImportCatalogZip`, `ImportTemplates` (8 sites)                                                                                                      | dead code                                                             | with stencil version removal |
| `ExportResources` still selects `sv.id AS version`                                                                     | `catalog/queries/ExportResources.kt`                                                                                                                 | unused column                                                         | with stencil version removal |
| Import ignores the v8 data-example `key`, assigns a random UUID                                                        | `ImportCatalogZip.kt:698`, `InstallFromCatalog.kt:267`                                                                                               | data-example keys must round-trip (`a30bfd6`)                         | step 5                       |
| `FontRef.namespace`/`registry` ignored by parser and resolver                                                          | `FontRefs.kt`, `FontFamilyResolver`                                                                                                                  | cross-registry font references                                        | step 4                       |
| MCP `CatalogInfo` DTO keeps `iconAssetSlug`/`imageAssetSlugs` names                                                    | `epistola-mcp/.../CatalogInfo.kt`                                                                                                                    | naming drift from the contract                                        | step 8                       |
| Unresolved-font quality finding writes context key `slug`                                                              | `UnresolvedFontQualitySource.kt`                                                                                                                     | naming; changing it changes fingerprints                              | decide in step 8             |
| #1061 stopgaps: mark ready publishes the draft, status derived from `template_versions`                                | `ListCatalogDrafts`, `WorkingCopyStatus`                                                                                                             | replaced by catalog versions                                          | step 3                       |
| MCP draft tools still on template versions (incl. #1102)                                                               | `epistola-mcp`                                                                                                                                       | follow the version model                                              | step 8                       |
| Suite keys may violate the new key rule (3–50 chars)                                                                   | all resource tables                                                                                                                                  | needs a migration with the contract's pad/truncate rule; not measured | step 4                       |
| `docs/catalog-release-model-v2.md` outdated (D14 pins, no-pins, ready mark dropped)                                    | docs                                                                                                                                                 | plan of record is wrong                                               | step 0                       |
| `ConsumersApi` not implemented                                                                                         | rest-api                                                                                                                                             | pre-existing since 1.x                                                | not 2.0                      |
| ADR 0003 open question: undeploying a release another deployed release depends on                                      | contract                                                                                                                                             | refuse symmetrically?                                                 | step 7                       |
| ADR 0001 rule missing: a release depending on a hotfix cannot be published                                             | contract                                                                                                                                             | noted in ADR 0003                                                     | after 2.0.0                  |
| Merged remote branches not deleted: `feat/render-from-release`, `feat/environment-deployments`, `feat/contract-2`      | GitHub                                                                                                                                               | housekeeping                                                          | any time                     |
| Main's open #1107 sets `1.5.1-SNAPSHOT`; release/2.0 already has it                                                    | `gradle.properties`                                                                                                                                  | next main merge should be clean                                       | next main merge              |
| `ExpressionClipboardPlaywrightUiTest` fails on this machine only, passes on CI                                         | UI tests                                                                                                                                             | local noise                                                           | —                            |
| Dev DB needs a reset: `V20261001154558` changed in place                                                               | local                                                                                                                                                | Flyway checksum                                                       | —                            |

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

- Stencils: does moving an instance on stencil ready leave the template's ready mark as it was?
- Stencils: how is "held back" known — a digest of the stencil content stored on the instance, or
  a comparison of the copy (fills removed) with the stencil's ready content?
- Stencils: inserting a stencil that was never marked ready — allowed, from its working copy?
- Stencils: cross-catalog instances move when the dependency pin moves?
- Stencils before template versions: propagation writes into template drafts (as
  `UpdateStencilInTemplate` does today) until template versions go too.

- Stencil pins on import: stopgap, or remove stencil versions now?
- Catalog identity in the schema: registry/namespace in every key, or a surrogate key?
- Default registry when none is configured (the installation's host?).
- Publish contract snapshots to Sonatype so `release/2.0` CI runs?
- Where `progress.md` lives once the catch-up branch merges (committed, or kept local).

## Notes

- Run Gradle with `--no-watch-fs`; worktrees need `pnpm install --frozen-lockfile && pnpm build`.
- Don't trust a background command's exit code; check for `BUILD SUCCESSFUL`/`FAILED` and the
  test-result XML timestamps.
