# Findings: newcomer user test and 1.x → 2.0 upgrade test

Open findings from the October 2026 tests on `feat/contract-v2-catchup`, to fix one at a time. Each
fix gets a failing-before / passing-after test and a changelog fragment. Tick an item when it lands
and name the commit. What was already fixed during the tests is listed at the end.

Status: `[ ]` open · `[~]` needs a decision first · `[x]` done

## User test: a first-time user

### Getting started

- [x] **U1. No "start here".** The tenant home shows Getting started (template, publish, deploy,
      document), each marked when done, until all are (`GetGettingStarted`). Evidence:
      `GetGettingStartedTest`, `TenantHomeOverviewTest`.
- [x] **U2. No demo credentials hint.** The login page lists the accounts under the `local` profile
      only (`localAccountHints`); elsewhere they are real credentials. Evidence: `LocalAccountHintsTest`.
- [x] **U3. No way to generate a test document from the UI.** An environment's page has "Try a
      document": a deployed template, as served there, with its first example data, as a PDF
      (`EnvironmentHandler.tryDocument`, `PreviewDocument`). Evidence: `EnvironmentTryDocumentHtmxTest`.

### Editor

- [x] **U4. Icon-only actions.** Variant cards: "Open editor" and "Preview" carry text. Catalog rows were
      fine (the name is the link, icons have tooltips); the editor's JSON and shortcut buttons have
      tooltips and labels, kept as icons. Evidence: `VariantReadyHandlerHtmxTest`.
- [x] **U5. Internal node ids visible.** Gone from the canvas block headers; the inspector labels it "ID".
      Evidence: `EpistolaCanvas.hints.test.ts` "names the block by its kind" (fails without it).
- [x] **U6. Truncated tabs hide Quality.** The tabs wrap instead of scrolling (`sidebar.css`). Evidence:
      `EditorSidebarTabsUiTest` with Quality and AI on (fails without it: Document ended at 276 of 260 px).
      The scroll-arrow code in `EpistolaSidebar` no longer triggers; left in place.
- [x] **U7. "sp" unit unexplained.** Unit menus and options carry `unitTitle` ("1 sp = N pt" from the
      theme). Evidence: `style-inputs.test.ts` "unit explanations" (fails without it).
- [x] **U8. Unsaved work restored silently.** Not a bug: the template editor keeps no document in the
      browser (only panel preferences), and autosaves to the server 3 s after a change (`SaveService`),
      warning on leave while a save is pending. In the test the leave warning was dismissed by the
      script (which keeps the page open), the autosave ran, and the block was simply saved.
- [~] **U9. Walkthrough off by default.** The editor walkthrough would help exactly the users who
  never find the switch. Decide: on for first visit, or offered once.

### Wording that misleads

- [x] **U10. Readiness labels read as a required step.** Neutral "Not checked yet" badge; the stencil says
      publishing checks and marks it. Evidence: `StencilHandlerHtmxTest`, `VariantReadyHandlerHtmxTest`.
- [x] **U11. Data contract opens with a red error.** The example message waits for unsaved changes.
      Evidence: `EpistolaDataContractEditor.clientValidationBanner.test.ts` (fails without it).
- [x] **U12. Data contract "Save draft" clashes with catalog drafts.** Now "Save", "Saved", "Unsaved
      changes"; `ContractSaveBar` tests and `DataContractSaveBarUiTest` updated.

### Consistency

- [x] **U13. UTC vs local times.** Server-formatted date-times carry `data-local-time`; `behaviors.js`
      shows them in the browser zone, UTC in the title. Audit and logs stay labelled UTC; ops dashboards
      keep their own. Evidence: `LocalTimeUiTest` (Tokyo; fails with the script off).
- [x] **U14. Raw enums.** `#labels.of(...)` (epistola-web `LabelsDialect`) on Quality, Feedback, profile
      and generation pages. Evidence: `LabelsTest`, `ProfileHandlerHtmxTest`, `GenerationHistoryRoutesTest`.
- [x] **U15. Inline template settings give no feedback.** Any successful non-GET request refreshes the
      bar status (`nav.js`), so an inline save shows as "1 change not published". Evidence:
      `TemplateSettingsUiTest` "an inline save refreshes…" (fails without it).

### Contract

- [~] **U16. `versionId` empty on generated documents.** By contract `DocumentDto.versionId` is null
  for a document rendered from a release, which reads as missing data. Proposal: always fill it
  (contract wording change in `epistola-contract`).

## Upgrade test: 1.x → 2.0

- [~] **G1. Live output changes after the upgrade.** Verified side by side (1.5.0 vs 2.0, same data,
  `sectioned-letter`, production; PDFs in the scratchpad `upg/v1-production.pdf`, `upg/v2-production.pdf`):
  - an environment that served an older 1.x version gets the latest published content once deployed
    (1.x production had no marker, 2.0 shows the `[v2]` content);
  - stencil instances pinned to an older stencil version render the stencil as it is now
    (`[stencil v2]` on every page).

  Option: create a release per distinct activation set, so each environment keeps what it served.
  Until decided, `docs/upgrades.md` ("Documents can look different after the upgrade") warns.

- [x] **G4. Releases render without the system catalog's fonts.** Fixed in `30c6dd0e1`. 1.x rendered
      Inter, 2.0 Helvetica (`Font ref 'inter' (catalog 'system') could not be resolved`). Not
      upgrade-specific: the system catalog's release kept every font with `"variants": []`, on a
      fresh 2.0 demo too, because `RecordInstalledRelease` retained content built by `ExportFonts`,
      which leaves classpath faces out. The retained revisions now carry the bundled faces by hash.
      Evidence: `ReleaseRenderSourceTest` (fails without the fix); a 1.5.0 database upgraded with the
      fixed build renders in Inter. **Existing 2.0 databases** (the local demo, dev databases) keep
      the faceless system release until the system catalog's version is bumped, because recording
      it is idempotent: recreate them, or bump the system catalog when this ships.
- [x] **G6. The system catalog's release could not be exported.** Fixed: it is no longer offered
      (catalogs list) and `ExportCatalogZip` refuses it with `SystemCatalogNotExportableException`.
      Cause: its release records the publisher's fingerprint (`catalog.json`), but what it retains
      also holds the seeded fonts, so the two never match; re-recording the fingerprint is not an
      option, since dependents pin the system release by it. Reachable before through the list's
      Export button, answering raw JSON. Evidence: `CatalogListHandlerTest`,
      `ExportUsesReleasedVersionTest`.
- [x] **G10. The system catalog could be deleted.** `DeleteCatalog` refuses it like `default`, and
      the list disables its button. Not for safety: what uses it (content, pinning releases, served
      releases) was already refused. Where nothing used it, the delete went through and did not
      last, since `SystemCatalogBootstrap` installs it again on every start. Evidence:
      `CatalogDeletionTest` (fails without the guard).
- [x] **G7. A generation job named the wrong catalog.** Fixed: `GetGenerationJob` did not select
      `catalog_key`, so every item fell back to the model's default (`default`), and the registry and
      namespace looked up by it were the default catalog's. Rendering was always right. Not
      upgrade-specific. Evidence: `DocumentQueriesTest` "names the catalog of each item".
- [x] **G5. Stencil parameters rendered empty after the upgrade.** Fixed ("declare again the stencil parameters templates still bind"), the
      simple way: a parameter an instance binds that its pinned version declared but the stencil no
      longer does is declared again, optional, on the working copy and the latest published
      version, and reported. Instances stay references. Cause in 1.x: an update leaving the schema
      out cleared it (G8). Evidence: `TemplateContentV8MigrationTest`; end to end, the upgraded
      letter is identical in text to 1.x apart from the G1 markers (16 pages, Inter, company name
      13 times).
- [ ] **G9. An instance inlined by the upgrade loses its parameters.** When a copy differs from its
      pinned version (or its stencil or version is gone), the upgrade inlines it as plain content
      and removes the stencil node, which is what supplied `params`; a copy reading `params.x`
      then renders it empty. Fixing it means rewriting each `params.x` to its binding or default.
      Not seen in the test data; how common it is in real 1.x tenants is unknown.
- [x] **G8. A stencil content save through REST dropped its parameters.** The cause behind G5's
      data: `updateStencilContent` cleared the schema when `parameterSchema` was left out, by
      contract ("full replacement"); 1.5.0 did the same. The editor and MCP were not affected. Now
      left out or null keeps it and a schema declaring no parameters (`{}`) removes it, stored as
      no schema. Contract wording `b4ad6ed` (feature/v2); suite: `UpdateStencil` / `CreateStencil`
      treat an empty schema as none, REST no longer clears on omission. Evidence:
      `StencilParameterSchemaApiTest` (fails with the old REST mapping), `StencilIntegrationTest`.
- [~] **G2. No guard against a live 1.x node during the migration.** The 1.x node fails closed (500s,
  writes nothing), so no data is lost, but nothing stops the operator either. Option: refuse a
  breaking migration while another node heartbeated in the last minute, with an override flag.
- [x] **G3. 404 `no-release-deployed` right after the upgrade.** The detail now says "published version"
      and where to deploy (the environment's page, or `deployCatalogVersion`); the tenant home already
      names environments that serve nothing (`GenerationReadinessHomeNoticeContributor`). Evidence:
      `ReleasesAndDeploymentsApiIT` asserts the detail.

## Design to decide

- [~] **D1. One storage model for drafts and releases.** Discussed 2026-10-19, not written up yet. A
  draft is stored as rows per version (`template_variants`, `themes`, …) and a release as a
  manifest of content revisions stored once (`release_entries` → `resource_revisions`). Every
  page and list reads the rows, so a released version cannot be browsed in the tables; only
  export, deployment and rendering read releases, through `ReleaseContentAssembler`. Proposal:
  every version, draft or release, is a manifest mapping each resource to a revision in one
  store. Saving a draft writes a revision and moves the draft's pointer; publishing freezes the
  manifest without copying; any version is read, listed and compared the same way. Cost: small
  per-version index rows (key, name, references, updated) for lists, search and "what uses
  this"; optimistic locking moves to the pointer; a migration rewriting how drafts are stored.
  Rejected stopgap: unpacking a release back into rows to show it (duplicates what D1 removes).
  Next step: a design note (or contract ADR, since it touches the release model) before
  building, after the `release/2.0` merge.

## Ideas for later

- **F1. A data contract as its own catalog resource.** Templates and stencils would reference a
  contract instead of each carrying one, so contracts can be reused, and several included or
  merged into one. (Raised 2026-10-20; not designed yet.)

## Still to verify

- [x] **V1. Full suites after the last fixes.** `./gradlew test uiTest --continue` on 2026-10-20:
      4467 tests, 1 skipped, 2 failed. `EpistolaFontApiTest` "get single system font returns its
      variants" listed each bundled face twice, a side effect of G4 (the API added the installation's
      faces to a release that now keeps them); fixed by adding only faces the release lacks.
      `EmbeddingBridgeUiTest` "clicking a boosted row link" passed on rerun: flaky, as noted in
      progress.md before. Both pass on rerun.
- [x] **V2. `NoTenantWideListTest` guard.** Passes (guards: 27 tests, 0 failed, 2026-10-20).
- [ ] **V3. Clean up the test machinery.** Databases `epistola_v1`, `epistola_upg`…`epistola_upg4`
      in `epistola-postgres`, stopped containers `epistola-v1` / `epistola-v1b`, and the image
      `ghcr.io/epistola-app/epistola-suite:1.5.0`.

## Already fixed during the tests

- `9fcc314bc`: "long-term work" renamed to "parallel work".
- `47ca663c1`: publishing parallel work decides where everyday work continues; "promote" removed.
- `a259db3b4`:
  - Create buttons hidden on subscribed (read-only) catalogs.
  - The release number is suggested correctly for an older line.
  - The getting-started hint names the namespace.
  - Next-version labels read "In it:" and "Changed:".
  - The generation dashboard has an empty state, and its statuses read as words.
  - Role texts corrected.
  - Smaller copy fixes, a duplicate Production badge removed, "· up to date" made visible.
- `43d188555`: requests queued before the upgrade log one warning, not a stack trace each.
- `feb62bbe9`: an upgrade held back by a missing catalog names that catalog.
