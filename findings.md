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

- [ ] **G4. Upgraded releases render without their fonts.** 1.x: Inter (embedded), 16 pages. 2.0:
      Helvetica, 14 pages, the title no longer bold. The log says
      `Font ref 'inter' (catalog 'system', weight 400) could not be resolved; falling back to the
      built-in font`. The fonts are there: `system` is installed at 2.0.0 with 9 fonts, has a
      `catalog_releases` row, and `epistola-demo` 5.21.1 pins it. Release renders read other catalogs
      through `DraftDependencyView.released` / `releasedBinary` (retained release content), so the
      suspicion is that the retained content of the installed `system` 2.0.0 lacks the font
      resource or its bytes. Not yet proven. Not seen in the fresh demo log, so it looks
      upgrade-specific, but that is also unproven.
- [ ] **G5. Stencil parameters render empty after the upgrade.** The letterhead's company name
      ("Globex Corporation", from `companyName`) shows in 1.x header and footer on every page
      (13 times) and in 2.0 once, outside the stencil. The upgraded reference keeps
      `parameterBindings: {companyName: companyName}` and `paramsAlias: params`, so the binding
      survived; why it evaluates empty is not yet known. Check whether the current stencil content
      reads `params.companyName`, and whether a fresh 2.0 stencil reference fills it.

- [~] **G2. No guard against a live 1.x node during the migration.** The 1.x node fails closed (500s,
  writes nothing), so no data is lost, but nothing stops the operator either. Option: refuse a
  breaking migration while another node heartbeated in the last minute, with an override flag.
- [ ] **G3. 404 `no-release-deployed` right after the upgrade.** Correct, but surprising to a client
      that worked a minute ago. Consider a clearer problem detail naming the environment and the
      deploy step, and a tenant-home notice until something is deployed.

## Still to verify

- [ ] **V1. Full suites after the last fixes.** Only targeted tests ran for `43d188555` and
      `feb62bbe9`. Run `./gradlew test uiTest`.
- [ ] **V2. `NoTenantWideListTest` guard.** Its last run found no test results; rerun
      `./gradlew --no-watch-fs :modules:guards:test --tests "*NoTenantWideList*"`.
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
