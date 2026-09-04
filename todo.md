# Relocation branch — remaining work

> Temporary working list for `feat/catalog-resource-relocation-alpha`. **Delete before merge.**
> Source: review of the branch on 2026-09-04. Items are ranked; file references are current.

## Before merge

- [x] **1. Old template addresses must keep working on REST, UI and MCP.** Aliases are only
      consulted by export (`CatalogContentBuilder`), the graph (`GetTenantResourceGraph`) and
      write-time canonicalisation (`TemplateDocumentPreparation`); every other surface 404s on the
      old `(catalog, template)` address after a move. **Decided: wire
      `ResolveCatalogResourceAddress` into the template lookups now** (ADR 0014 step 7) — UI GETs
      redirect to the canonical URL, REST/MCP resolve transparently.
- [x] **2. Read `template_resource_id`.** It is written by trigger and never read. `ListDocuments.kt:61`
      and `CountDocuments.kt:38` filter on `template_key` alone, so a rename hides a template's
      generation history. Filter on `template_resource_id`, falling back to the address for rows
      that predate the column
      (`OR (template_resource_id IS NULL AND catalog_key = … AND template_key = …)`).
- [x] **3. Reserve vacated attribute addresses.** `requireAddressAvailable` is wired into
      `CreateStencil` and `CreateDocumentTemplate` but not `CreateAttributeDefinition`, while the
      planner's `isAddressTaken` counts a retained attribute alias as occupying. Add the call, plus a
      guard test: for every `MovableResource` entry, creating at a vacated address is rejected.
- [x] **4. Aliases left behind by `UnregisterCatalog`.** The alias table has no FK on `catalog_key`
      (deliberate). Deleting a catalog resources were moved _out of_ leaves its addresses reserved
      with no page to release them from. **Decided: delete those aliases in `UnregisterCatalog`.**
      Published references to the deleted catalog's addresses stop resolving from then on.
- [x] **5. Stale copy.** `KnownFeatures.kt:141` ("supports stencils only") and
      `docs/catalog-resource-relocation.md:8` ("first vertical slice supports stencils").
- [x] **12. `DataPreservationMigrationIT` seeds no `documents` and no `variant_attribute_definitions`**
      — the tables touched by the PK swap (`V20260828132828`) and the FK drops on partitioned
      tables (`V20260831161109`). Add a row of each.
- [ ] **14. `docs/engineering-review-brief.html`** is untracked and unrelated (post-1.1.0 review).
      Keep it out of this PR.

## Decisions

Recorded here once taken; each has a recommendation in the review.

- [x] **6. Legacy relative references — decided: backfill unreleased catalogs; implemented as a
      move-time pin of the moving resource's own versions (same scope, no mass migration).** The `immutable-relative-reference` blocker fires for any
      published stencil version, ever, with an unqualified nested-stencil reference — so such a
      stencil can never move. Option F does not fix it (`target` is only populated on write; stencil
      versions have no retention). Only fix: a one-time, meaning-preserving qualification backfill
      of published `stencil_versions` / `template_versions`. Measure first: count published stencil
      versions containing a stencil node with `catalogKey: null`. One-time migration qualifies
      relative references in published `stencil_versions` / `template_versions` for catalogs without
      a release. Record the exception to "immutable JSON is never edited" in ADR 0014 and the docs.
- [x] **4. Aliases on catalog delete — decided: delete them** (see before-merge item 4).
- [x] **1. Alias-aware REST/UI template lookups — decided: this PR.** Old `(catalog, template)`
      addresses resolve through `ResolveCatalogResourceAddress` on REST and UI routes (and MCP if it
      looks templates up by address). Replaces the preview-warning idea.
- [x] **10. More types this week? — decided: no.** Freeze at stencils, attributes, templates;
      `code_lists` is the next PR. `code_lists` is cheapest next (one FK + `code_list_entries`).
      Themes/fonts/assets need their render-time lookup made alias-aware first
      (`MovableResourceGuardTest` enforces this).
- [ ] **13. `./gradlew resetLocalDb`** — required before manual testing (local DB was last migrated on
      `feat/publish-to-exchange`). Destructive to local data.

## Deferred (tracked, not for this PR)

- **7. Released catalogs block all moves** (`released-resource`). Kept blocked by decision. The
  handoff design (ADR 0014 step 11) is what makes the feature usable on mature catalogs —
  minimal version: allow the move, carry the alias in the next release manifest, warn that
  subscribers on the previous release see the old address until they upgrade.
- **8. Planner cost scales with the tenant.** A stencil move parses every `template_versions` and
  `stencil_versions` row (`loadTemplateVersions` / `loadStencilVersions`, unfiltered) and builds
  the full graph for the cycle check — at preview and again under the lock. Log the row counts so
  the slow tenant is noticed. Removed by Option F's `target` / `resolved_references`.
- **9. Stencil-pin export precheck gap.** `FindStencilVersionExportConflicts.kt:66–72` scopes
  stencils by `s.catalog_key = :catalogKey`; a moved stencil drops out and a template pinning a
  non-latest version of it passes the precheck silently. Resolve pinned references by identity.
- **Co-move suggestions** — out of scope by decision; keep in mind.
- **Alias expiry / cleanup** — aliases never expire by decision.

## Delivery

- [ ] **11. Push and open a draft PR.** 22 commits, +6.6k/−5.2k over 173 files, never pushed; CI has
      never seen the branch. Review in commit order. PR description must call out what affects
      tenants with the toggle **off**: identity triggers on 7 tables, write-time qualification
      changes stored content for everyone, exports now emit stencil-borne dependencies.
- [ ] CHANGELOG entries exist per commit; re-read them as one block before the PR — several describe
      intermediate states ("first alpha supports stencils").
- [ ] Final gate: `./gradlew ktlintCheck unitTest integrationTest` fresh (check test-result mtimes
      against source mtimes — Gradle's UP-TO-DATE has fooled us once), `pnpm format:check`,
      `pnpm license:check`, then `./gradlew uiTest`.
- [ ] Delete this file.

## Keep as is

Lock → replan → fingerprint → per-row guard; `MovableResource` as the _last_ step of making a type
movable with `MovableResourceGuardTest`; one traversal (`ResourceReferenceSites`), stored absolute /
wire relative; generation history pins by identity with no backfill; all-or-nothing batches with
handover ordering and swap refusal; organise page as a separate deep-linkable browser.
