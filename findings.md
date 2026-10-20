# Findings: newcomer user test and 1.x → 2.0 upgrade test

Open findings from the October 2026 tests on `feat/contract-v2-catchup`, to fix one at a time. Each
fix gets a failing-before / passing-after test and a changelog fragment. Tick an item when it lands
and name the commit. What was already fixed during the tests is listed at the end.

Status: `[ ]` open · `[~]` needs a decision first · `[x]` done

## User test: a first-time user

### Getting started

- [ ] **U1. No "start here".** A new user lands on the tenant home with no path through catalog →
      template → publish → deploy → generate.
- [ ] **U2. No demo credentials hint.** The demo login page does not say which accounts exist.
- [ ] **U3. No way to generate a test document from the UI.** The only route is the REST API, which
      needs the vendor media type and a namespace. A "Try it" from a template or environment would
      close the loop.

### Editor

- [ ] **U4. Icon-only actions.** Several toolbar and block actions have no visible label.
- [ ] **U5. Internal node ids visible.** Raw node ids appear in the editor where a name is expected.
- [ ] **U6. Truncated tabs hide Quality.** At ordinary widths the editor tabs cut off, and the
      Quality tab is not reachable without knowing it is there.
- [ ] **U7. "sp" unit unexplained.** Spacing values use `sp` with no hint of what it means.
- [ ] **U8. Unsaved work restored silently.** Reopening a template brings back unsaved local edits
      without saying so, or offering to discard them.
- [~] **U9. Walkthrough off by default.** The editor walkthrough would help exactly the users who
  never find the switch. Decide: on for first visit, or offered once.

### Wording that misleads

- [ ] **U10. Readiness labels read as a required step.** "Ready" looks like a gate, not a marker.
- [ ] **U11. Data contract opens with a red error.** An empty contract shows an error on entry,
      before the user has done anything.
- [ ] **U12. Data contract "Save draft" clashes with catalog drafts.** Same word, different thing.

### Consistency

- [ ] **U13. UTC vs local times.** Some pages show UTC, others local time, without saying which.
- [ ] **U14. Raw enums.** Quality, Feedback and the profile page still show values like `IN_REVIEW`.
- [ ] **U15. Inline template settings give no feedback.** Saving shows nothing on success.

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
- [ ] **G6. The system catalog's release cannot be exported.** `ExportCatalogZip(tenant, system)`
      fails with "Release 2.0.0 of catalog 'system' rebuilds to fingerprint … but was released as …;
      its retained content has been altered", with or without the G4 fix. The recorded fingerprint
      is the publisher's; the retained content rebuilds to another. Check whether the UI offers
      this export, and whether the seeded fonts are what make the two differ.
- [ ] **G7. A generation job names the wrong catalog.** Rendering `epistola-demo/sectioned-letter`
      returns a job item with `catalogKey: "default"` (and `releaseVersion: 5.21.1`, which is
      epistola-demo's). Seen on the upgraded database; not yet checked on a fresh one.
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
- [ ] **G3. 404 `no-release-deployed` right after the upgrade.** Correct, but surprising to a client
      that worked a minute ago. Consider a clearer problem detail naming the environment and the
      deploy step, and a tenant-home notice until something is deployed.

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

- [ ] **V1. Full suites after the last fixes.** Only targeted tests ran for `43d188555` and
      `feb62bbe9`. Run `./gradlew test uiTest`.
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
