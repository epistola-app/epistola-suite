# Testability Improvements — Plan

Status: **draft for discussion**
Owner: TBD
Last updated: 2026-05-21

## Why this plan exists

The suite's test culture is integration-first: Testcontainers PostgreSQL, the
real Spring context, the real mediator. That catches integration regressions
fast and avoids the "mocks pass but prod breaks" trap. The trade-off is real:
the feedback loop is longer than it needs to be for code that has no
integration concerns, and writing a new test is a heavier commitment than it
should be — which silently discourages writing them.

Two recent observations sharpen the case:

1. **New modules ship with token coverage.** `modules/feedback/` has 1 test
   file across 41 sources. `modules/epistola-support/` has 2 tests across 3
   sources. The high-friction first test is the most expensive one; once an
   author has paid that cost, density follows. Right now they're not paying
   it.
2. **Building an external end-to-end suite against `demo.epistola.app`
   surfaced specific friction**: only 1 `data-testid` attribute exists across
   the entire Thymeleaf template set (`editor-container` in
   `templates/editor.html`). External tests fall back to brittle structural
   selectors. The wishlist accumulated in `website` repo
   (`src/content/tests/testid-changes.md`) is the de-facto backlog.

Goal: keep the integration-first defaults but lower the friction so the suite
stays healthy as the codebase grows, and make the existing UI testable from
the outside without re-discovering selectors every time.

## What's already strong (preserve these)

- **`Scenario` DSL + `TestFixture` Given-When-Then** in `modules/testing/`.
  Reads well, type-safe, LIFO cleanup. Don't rebuild this.
- **Testcontainers with reuse + UNLOGGED tables + `FakeDocumentGenerationExecutor`**.
  Smart performance trade-offs already paying off.
- **CI staging** (`unitTest` / `integrationTest` / `uiTest` separate Gradle
  targets, `test-ui` job hardened against flakiness). Don't merge or simplify
  these — the separation is load-bearing.
- **`UiTestHygieneTest`**. Bans `waitForTimeout`, `:visible` pseudo, stderr
  prints. The "ratchet" pattern (a test that fails the build when discipline
  slips) is worth keeping and possibly extending.
- **Kover coverage aggregation** with module-level XML reports.

## Proposals

Six concrete improvements, ordered by leverage (highest first).

### 1. Adopt `data-testid` as a first-class authoring concern

**Problem.** External tests (and internal Playwright tests where structural
selectors are weak) anchor to whatever they can find. Helpers in the website
repo's `src/demo-reel/` repeatedly hit issues like "two buttons match
`.ep-btn-primary` and one of them is Logout".

**Proposal.**

- Adopt a naming convention: `data-testid="<area>-<element>"` (e.g.
  `tenant-create-submit`, `theme-list-row-{slug}`, `template-tab-variants`).
  No camelCase, no per-row stable IDs unless deletion is in scope.
- Add `data-testid` attributes for: forms (submit/cancel), list rows
  (anchored to slug or stable ID), tabs, top-level page action buttons,
  confirm-dialog accept/cancel, status badges, and HTMX swap targets the
  external suite already references.
- Backfill from the website wishlist as the input list. Future selectors
  added via PR — code review enforces the convention.
- Add a `UiTestidHygieneTest` to the ratchet: every new `.ep-btn-primary`
  inside a form card must carry a `data-testid`. Static analysis over the
  rendered template set; failing build forces the convention.

**Effort.** ~1 week. Mostly mechanical templating edits.

### 2. Lower the bar to write a non-integration test

**Problem.** New domains tend to ship with one integration test or none.
The implicit message is "tests are heavy". For business logic that doesn't
need Spring + DB, that's a self-imposed tax.

**Proposal.**

- Document (in `docs/testing.md`) when to choose unit vs integration.
  Rule of thumb: pure functions and command/query validation get unit tests;
  anything that touches the mediator or the DB gets integration.
- Provide one model unit-test file per untested domain
  (`modules/feedback/`, `modules/epistola-support/`) that demonstrates the
  expected shape. These act as "starter examples" — opinion-setting code,
  not exhaustive coverage.
- **Do not introduce MockK/Mockito by default.** The codebase has chosen
  zero-mocking and it's a strength. Exceptions: pure unit tests of
  collaborators-as-arguments (e.g. validators, mappers, formatters) — those
  can be tested without mocks because their dependencies are pure too.

**Effort.** ~3 days. Mostly authorial.

### 3. Densify handler tests for HTMX contracts

**Problem.** 9 `*HandlerTest.kt` files across `apps/epistola` covering
~92 handler sources. UI tests (Playwright) catch end-to-end regressions
but are slow and broad. The middle layer — "this POST swaps the right
fragment with the right content" — is sparse.

**Proposal.**

- Define a `BaseHandlerTest` extending `IntegrationTestBase` with
  `TestRestTemplate` pre-wired and a couple of helpers (`postFormAndExpectSwap`,
  `getAndExpectFragment`). Make the first handler test for a new
  endpoint a 15-line affair.
- Target the surfaces with the most HTMX-driven swap logic first:
  `tenants/` (create flow, switcher), `themes/` (default-theme star button),
  `catalogs/` (create dialog), `templates/` (variant dialog).
- Aspirational density: one handler test per non-trivial HTMX action. ~30
  new handler tests total.

**Effort.** ~2 weeks. Higher than #1 because each test is bespoke.

### 4. Make the external test suite a first-class consumer

**Problem.** The website-repo end-to-end suite runs against
`demo.epistola.app` and is the only place where many flows are tested at
the browser level. It's pinned by its dependency on selectors that don't
have a contract.

**Proposal.**

- Treat `data-testid` attributes referenced by the external suite as a
  **soft contract**. Removing one is a breaking change for that consumer;
  call it out in PR review.
- Add a CI step (later — not in the first wave) that runs the external
  suite against a freshly-built suite image on PR. The website-repo's
  `pnpm test:scenario` is already CI-friendly.
- Document where `data-testid` attributes are sourced from (the wishlist
  at `epistola-app/website:src/content/tests/testid-changes.md`) so it
  doesn't drift.

**Effort.** ~3 days for soft-contract docs + review checklist. CI
integration is a separate effort, ~1 week.

### 5. Close the schema-validation TODOs

**Problem.** `DocumentGenerationIntegrationTest.kt` contains
`TODO: This test requires schema validation to be implemented` and
`TODO: Add item with schema-violating data once validation is implemented`.
The test is asserting future behavior that doesn't exist yet — confusing
for new contributors and a silent blocker on actual coverage.

**Proposal.**

- Decide: ship the validation, or remove the TODOs and document the
  intentional gap. Both are fine; the limbo state isn't.
- If shipping: design the validation in JSON-schema (the
  `modules/epistola-core/validation/` namespace already exists), wire
  through the command path, then un-TODO the test.

**Effort.** Variable. The decision is ~30 min; the implementation is
~1 week if validation ships.

### 6. Editor module: lift TypeScript test coverage

**Problem.** 78 Vitest test files across 229 TypeScript sources (~34%
file-level coverage). The editor is the riskiest part of the UI — Lit +
ProseMirror integration, paste handling, schema marshalling. Visual
regressions here are subtle and easy to miss.

**Proposal.**

- Add a `vitest --coverage` job to CI (alongside Kover for Kotlin) so the
  number is visible and starts trending up rather than down.
- Identify the 5 highest-risk untested files (the ones with the most LOC
  AND no test counterpart). Author one test each.
- Don't aim for 80% — aim for the riskiest 20% covered first.

**Effort.** Coverage CI = ~1 day. First-tests-for-risky-files = ~1 week.

## Phasing

| Phase | What                                                                          | Effort   | Why first/later                                           |
| ----- | ----------------------------------------------------------------------------- | -------- | --------------------------------------------------------- |
| 1     | Proposal #1 (data-testids) + Proposal #2 (unit-test starter examples)         | ~2 weeks | Unblocks the external suite + lowers friction immediately |
| 2     | Proposal #3 (handler test density) + Proposal #5 (schema validation decision) | ~2 weeks | Builds on Phase 1; decision on #5 informs scope           |
| 3     | Proposal #4 (external suite as CI consumer) + Proposal #6 (editor coverage)   | ~2 weeks | Lower urgency, harder to land cleanly                     |

Each phase is one PR or a small stack — none of these need to land all at
once. Phase 1 in particular is two independent efforts that can be
parallelised across contributors.

## Success criteria

After all three phases:

- Every form, list row, tab strip, and HTMX swap target in
  `apps/epistola/src/main/resources/templates/**/*.html` carries a
  `data-testid`. The website-repo external suite stops anchoring on
  `.ep-btn-primary`-style structural selectors.
- `modules/feedback/` and `modules/epistola-support/` have at least one
  unit test per domain concept (>= 5 tests each).
- Handler tests double from 9 → ~25, all using the new `BaseHandlerTest`.
- `DocumentGenerationIntegrationTest.kt` has zero TODOs.
- Editor module has a Vitest coverage CI job and the five most-risky
  files have unit tests.
- New contributors can find "how to write a test" in `docs/testing.md`
  without asking.

## What this plan is _not_ doing

- Not introducing MockK/Mockito by default. The integration-first culture
  stays.
- Not rewriting the existing `Scenario`/`TestFixture` DSLs. They're good.
- Not chasing a coverage percentage as a goal. Coverage trending is
  useful as a signal; targeted improvement in risky areas is the
  outcome.
- Not converting integration tests to unit tests retroactively. Existing
  patterns stay where they are.
- Not yet wiring the external suite into CI on every PR. That's a
  follow-up, gated on Phase 1 + 2 stabilising the selectors.

## Open questions

- Owner for each proposal — currently TBD.
- Naming convention for `data-testid`: is `kebab-case-area-element` right,
  or should we prefer `data-test="..."` or `data-cy="..."`? Pick one
  before Phase 1 starts.
- Is JSON-schema the right vehicle for the schema-validation work, or
  should we look at something Kotlin-native (kotlinx.serialization +
  validation annotations)? Worth a short design doc before committing.
- Should `UiTestidHygieneTest` (the new ratchet) live in `apps/epistola`
  or `modules/testing`? Probably `modules/testing` so it can be reused.
