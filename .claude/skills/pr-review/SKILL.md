---
name: pr-review
description: Review a branch/PR against epistola's architecture conventions and recorded preferences. Use when reviewing code changes; complements /code-review (which finds correctness bugs).
---

Review a set of changes against the epistola-suite project conventions and our
recorded preferences. This is the **convention / architecture / altitude** layer
— it judges whether the change fits the project's rules and is well-shaped.

**It is NOT a correctness-bug hunt.** Run `/code-review` alongside (or after)
this for line-level bugs and simplification — the two do not overlap. End your
review by reminding the reviewer to do so.

**Input**: nothing (defaults to the current branch vs `main`), or a PR number.

## 1. Establish the diff

- Default — current branch: `git diff main...HEAD` plus `git diff` and
  `git diff --staged` for any uncommitted work. Also `git log main..HEAD
--oneline` for the commit shape.
- If given a PR number `N`: `gh pr diff N` (and `gh pr view N` for description /
  base branch).

Then write a 2–4 line summary: what changed, and **which modules / surfaces it
touches**. This drives which checklist sections below get deep attention — only
review sections relevant to the touched files. List the rest as N/A at the end
(see §4); don't pad the review with inapplicable sections.

## 2. Checklist

Each item: what to look for · why · how to verify. Scope to the touched files.

### Design quality (altitude)

Broader than `/code-review`'s line-level simplification — this judges the
_shape_ of the change.

- **Low coupling / high cohesion** — does new code live in the right module?
  Business logic belongs in `epistola-core`, not leaked into `apps/epistola`
  (which is UI host only). Feature modules talk to each other via SPIs
  (`NavContributor`, `FooterContributor`) and CQRS queries, **not** by injecting
  each other's services. Related logic sits together rather than scattered.
- **Simplicity** — is this the smallest design that works? Flag speculative
  abstraction, indirection with only one caller, interfaces with one
  implementation, config knobs nobody asked for.
- **Future-proofing — only where it's cheap and pre-committed.** Per CLAUDE.md,
  extension points are introduced one-at-a-time as features need them. Reward
  seams that match a _known_ upcoming need (the single RFC-7807 error seam; the
  separate hub metrics leg). Flag YAGNI scaffolding that guesses at unknown
  futures.
- **Maintainability** — names and idioms match surrounding code; no dead code or
  commented-out blocks; KDoc / `docs/*` / `CLAUDE.md` updated when a convention,
  API, or pattern they describe actually changed.
- **Test coverage** — new behavior has tests at the right tier (see Tests
  below); edge cases and failure paths covered, not just the happy path; no
  silently `@Disabled` / skipped / `xfail`ed tests left behind.

### Scope & hygiene

- **Branched off `main`** — the diff isn't bloated by an unrelated long-lived
  feature branch. If the base looks wrong, flag it (we want a clean path to
  `main`).
- **Backward compatibility — INVERTED rule for this repo.** epistola is
  pre-production: do **not** demand compat. Flag any back-compat shim, migration
  path, legacy-format tolerance, version-tolerance layer, "V2 keeps old
  behavior" mechanism, feature flag for old behavior, or engine-version bump
  done purely to keep old output deterministic — as **waste to delete**. The
  change should break directly (plus a destructive migration if needed). A
  transient defensive-parse _within a single PR's_ transition window is fine.
- **CHANGELOG.md** updated under `[Unreleased]` with a commit-style entry
  (`- [**[audience]** ]type(scope): **Title.** Description`), correct audience
  badge (`**[user]**` / `**[dev]**` / none), required `type(scope)`. Helm
  changes go in `charts/epistola/CHANGELOG.md`, everything else in root.
- **Installation / upgrade instructions** — if the change adds a config
  property, env var, an operator-impacting migration, or a new
  feature-toggle / support setting, the operator-facing docs (`docs/*`, Helm
  `charts/epistola/`, README / setup scripts) and changelog say what an operator
  must do to upgrade. A new knob with no documented default/effect is a finding.
- **Formatting & libs** — `ktlintFormat`/`ktlintCheck` clean, `pnpm format`
  clean. JSON uses Jackson 3 (`tools.jackson.*`), never `com.fasterxml.jackson`.

### Architecture boundaries

- **UI vs REST separation** — UI code (Thymeleaf / JS / TS) must **never** call
  `/api/**`; the REST API is for external systems only. UI needs get a UI
  handler. Verify: `./gradlew test --tests UiRestApiSeparationTest`.
- **Three-surface parity** — a capability change should be reflected across all
  three surfaces, or carry an explicit decision to scope to a subset: web UI
  (`apps/epistola` handlers + templates), REST (`epistola-core/api` +
  `modules/rest-api` OpenAPI spec), and MCP (`epistola-mcp`). Don't ship on one
  and silently drift the others.
- **Module ownership** — respected per CLAUDE.md (logic in `epistola-core`; UI
  host in `apps/epistola`; feature modules may ship their own UI via the
  `epistola-web` HTMX DSL + Nav/Footer contributors).

### Demo catalog & bundled-content version (PR blocker — high-miss)

- **Every** new/changed user-facing capability is demonstrated in the demo
  catalog (`modules/epistola-core/src/main/resources/epistola/catalogs/demo/`),
  with realistic variants + edge cases. CLAUDE.md item 13 makes this a hard
  blocker; if a feature genuinely can't be shown there, the PR must say why.
- **Any touch to bundled catalog resources** under
  `…/catalogs/{demo,system}/` MUST: bump `release.version` in that catalog's
  `catalog.json` (SemVer, strictly increasing) **and** regenerate
  `release.fingerprint`. The loaders detect changes by **fingerprint, not the
  version string**, so a stale fingerprint silently ships unchanged content.
  Verify: `./gradlew :modules:epistola-core:unitTest --tests
"*BundledCatalogFingerprintTest"`, paste the reported "actual" fingerprint,
  re-run green. **This is the single most-forgotten step — call it out
  explicitly whenever the diff touches those paths.**
- **Editor component** added/changed ⇒ registry
  (`modules/editor/.../engine/registry.ts`) and demo catalog kept in sync, and
  the `ComponentDefinition` has at least one `examples[]` entry (PR blocker).
- **Catalog exchange** impact considered (`modules/epistola-catalog/` — import/
  export, serialization, manifest schema, version handling).

### Data & migrations

- Migrations live under the **owning** module:
  `<module>/src/main/resources/db/migration/<module>/`, named
  `VYYYYMMDDHHMMSS__<module>_<desc>.sql` (UTC timestamp version). Versions are
  globally unique and ordered; a non-core migration that FKs to (or uses a
  `DOMAIN` from) core must timestamp **after** that core migration. **Merged
  migrations are never edited** — add a new timestamped file.

### Application time

- No raw `Instant.now()` / `OffsetDateTime.now()` / `LocalDate.now()` /
  `ZonedDateTime.now()` / `YearMonth.now()` in application code — use
  `EpistolaClock.*`. No injected Spring `Clock` for app time. Async/threaded
  work captures and binds `MediatorContext` (`MediatorContext.runnable(...)`).
  DB `NOW()` is fine for db-owned timestamps / leases / claim comparisons. Tests
  use `EpistolaClockExtension` / `testClock`, not wall-clock sleeps.

### Errors

- New error conditions add a `ValidationCode` enum value and pass it to
  `ValidationException`; messages are human-only (**no `SCREAMING_CODE:`
  prefix**). Any RFC-7807 / ProblemDetail work stays confined to the single
  `ValidationException.toValidationErrorResponse()` seam, not per-handler.

### Frontend / CSP

- No `hx-on::*` / `hx-on-*` attributes (blocked by CSP `eval` and mangled by
  Thymeleaf's `::`). Use an inline `<script>` with `addEventListener` instead.

### Feature toggles

- Toggle reads go through CQRS queries — `ResolveFeatureToggles` (internal /
  UI / schedulers) or `GetFeatureToggles` (permission-gated admin) — never by
  injecting `FeatureToggleService` directly.

### Tests

- Fixtures seed domain state by dispatching **commands** through the mediator,
  not raw `INSERT`. Raw SQL only when no command can produce the state (or to
  plant a specific historical timestamp), with a one-line comment saying why.
- UI tests use `PlaywrightHtmxSupport` helpers (`gotoAndReady`, `htmxSettle`,
  `openDialogByTrigger`, web-first assertions). Banned and build-failing:
  `waitForTimeout`, the `:visible` pseudo, blind `waitForSelector("…[open]")`,
  bare `page.navigate`, `System.err` dumps (enforced by `UiTestHygieneTest`).
  Prefer a handler-level `*HandlerHtmxTest` over a browser test for
  server-contract assertions.
- The right test tier was run for the change type (unit / integration / ui).

### Cross-surface specials (only when touched)

- **Fonts** — kept in sync across UI / REST / MCP / catalog / generation; asset
  & font media types are seeded `asset_types` rows, not a widened CHECK or a
  closed enum (branch on `AssetMediaCategory`). Consult `docs/fonts.md`. Note
  the still-open OFL attribution obligations if relevant.
- **Locale** — resolved once via `TenantLocaleResolver` (variant attr → tenant
  default → app default) and threaded to **both** editor preview and PDF so they
  agree. Consult `docs/locale.md` before changing resolution or `$formatDate` /
  `$formatLocaleNumber`.
- **Metrics** — node-identity tags present regardless of support tier;
  installation-wide gauges leader-elected (advisory lock), not per-replica; the
  hub OTLP leg is a separate registry we own, kept distinct from the customer's
  BYO-agent leg. There is no per-tenant metrics on/off toggle.

## 3. Verification commands

Run the ones relevant to the diff; cite results in findings.

```bash
./gradlew test --tests UiRestApiSeparationTest          # UI never calls /api
./gradlew unitTest --tests UiTestHygieneTest            # UI-test hygiene
./gradlew :modules:epistola-core:unitTest --tests "*BundledCatalogFingerprintTest"
./gradlew ktlintCheck                                   # Kotlin style
pnpm format:check                                       # all-file formatting
```

## 4. Output format

Group findings by severity:

- **Blocker** — must fix before merge (failing convention test, missing demo +
  fingerprint bump, UI calling REST, raw `now()`, back-compat cruft, missing
  `examples[]`).
- **Should-fix** — design/maintainability/coverage issues worth addressing.
- **Nit** — minor, optional.

Each finding: `file:line` · the rule it violates · a concrete fix.

Then:

- An explicit **N/A** line listing checklist sections that don't apply to this
  diff (so coverage is transparent).
- A closing reminder: **"Run `/code-review` for correctness bugs and
  simplification — this review covered conventions and altitude only."**
