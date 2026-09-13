# Agent Effectiveness and Consistency Review

> **Status:** Point-in-time review, 2026-09-11, of commit `aa063b251`. It records the state on that
> date. The recommendations are proposals, not current conventions: nothing here is a rule until
> it lands in the instruction files or the build.

Most changes to this repository are now made with AI coding agents. This review asks why those
agents have become less effective as the suite grew, and what to change. It complements the
[Architecture review](architecture-review.md) (2026-06-08), which covered module boundaries and
security.

**What was examined.** The repository at `aa063b251`: code, build, CI, `docs/`, `CLAUDE.md`,
`AGENTS.md` and `.claude/skills/`. Git history since 2026-06-11: 306 non-merge commits, 207 of
them by people. Aggregate signals from 81 local Claude Code sessions on this repository
(2026-06-18 to 2026-09-11): tool errors, file reads, skill use and the kinds of correction the
maintainer had to make. Vendor documentation for Claude Code and Codex, read from the source pages
on 2026-09-11.

**What was run.** Static scans (every command is in
[Appendix H](#appendix-h-reproducing-the-numbers)), `./gradlew ktlintCheck`, and a test-filter
experiment on `:modules:generation`. No integration, UI or CI runs.

## 1. Summary

The architecture is not the problem. All 282 command and query handlers follow one shape, every
message declares its authorization and tests check it, and the markup guards are strong. What
degraded is the layer agents work through: instructions, skills, guard tests and the verification
loop. `CLAUDE.md` grew 17× in nine months with nothing checking it. Agents now get stale,
contradictory and sometimes build-breaking guidance at the moment they write code, and they cannot
cheaply prove their work.

**Main causes** (detail in [§3](#3-why-agents-became-less-effective)):

1. Instructions only ever grow, and nothing tests them.
2. The guidance an agent follows while writing code is often wrong. All six code-writing skills
   teach removed or banned patterns, and `CLAUDE.md` carries more than twenty-five stale,
   contradictory or untrue statements.
3. Verification is slow, broken or undefined.
4. Rules and enforcement disagree. CI skips checks the docs say are enforced, one guard test
   cannot fire, and twelve guard tests are never mentioned to agents.
5. Context sinks: a 51 KB always-loaded `CLAUDE.md`, and a 738 KB `CHANGELOG.md` that 85% of
   commits edit.

**Top actions** (detail in [§5](#5-roadmap)):

| #   | Action                                                                                              | Tier | Effort |
| --- | --------------------------------------------------------------------------------------------------- | ---- | ------ |
| 1   | Quarantine the broken skills; fix the false and contradictory instructions                          | P0   | M      |
| 2   | Make CI run what the docs claim: `ktlintCheck`, `checkContractVersionAlignment`, stylelint          | P0   | S      |
| 3   | Fix the `./gradlew test --tests` trap and the changelog parser's handling of breaking changes       | P0   | S      |
| 4   | A slim canonical `AGENTS.md`, an import-only `CLAUDE.md`, area guides and path-scoped rule cards    | P1   | L      |
| 5   | Changelog fragments instead of one shared file                                                      | P1   | L      |
| 6   | Test the instructions (`agents:check`), repair weak guards, and ratchet drift counters              | P2   | M      |
| 7   | A `local-suite` skill plus SessionStart and Stop hooks, so "done" means the change was seen working | P3   | M      |

**What is strong, and should stay that way:**

- One CQRS shape for all handlers, guarded by `MediatorWiringTest` and `AuthorizationCoverageTest`.
- Clock discipline: no bare `now()` calls in main code.
- Markup guards for CSP, design-system classes, icons, page headers and input lengths.
- Forward-only migrations guarded by `checkMigrationVersions`.
- Only one Kotlin main file over 1,000 lines, and 6 TODO markers in the whole repository.
- A documentation index with status labels.
- ktlint passes on `main`.

## 2. Status scorecard

| Area                        | Verdict  | Evidence                                                                                                                                   |
| --------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| CQRS core and authorization | Strong   | 282 handlers, each in the same file as its message and annotated `@Component`; `MediatorWiringTest`, `AuthorizationCoverageTest`           |
| Time and concurrency        | Strong   | 0 bare `now()` in main; 0 `runBlocking`/`GlobalScope`; `ApplicationClockUsageTest` (gaps in [App. B](#appendix-b-guard-audit))             |
| Markup and CSP guards       | Strong   | `CspTemplateComplianceTest`, `DesignSystemClassTest`, `IconUsageTest`, `PageHeaderUsageTest`, `InputMaxLengthTest`                         |
| Module graph                | OK       | Core depends only on `generation` and `epistola-crypto`, but 10 of `apps:epistola`'s 15 project dependencies are wired by classpath alone  |
| Code size                   | OK       | 1 Kotlin main file over 1,000 lines; 5 editor TypeScript files over 1,000 lines                                                            |
| Editor                      | OK       | Strict TypeScript, but `*.test.ts` is never type-checked and custom elements use three tag-prefix styles                                   |
| UI layer consistency        | Drifting | Flat `handlers/` package (69 files, 23 declaring other packages); 103 `onNonHtmx {}` vs 1 file using `onFullPage {}`; 41 raw `render(...)` |
| Guard tests                 | Drifting | One cannot fire; three scan too little ([App. B](#appendix-b-guard-audit))                                                                 |
| CI gates                    | Drifting | `ktlintCheck`, `checkContractVersionAlignment` and stylelint never run in CI                                                               |
| Test feedback loop          | Drifting | `test --tests X` fails in modules without X; repo-wide guards live only in `:apps:epistola` and need `pnpm build`                          |
| Documentation               | Drifting | Good index, but 3 unindexed pages, an ADR number collision, 4 status mismatches, 5 duplication clusters                                    |
| Changelog                   | Drifting | 738 KB file; the in-app parser misfiles breaking entries; format not validated                                                             |
| Auto memory                 | Drifting | At least 4 stale notes loaded every session; one orphaned memory directory                                                                 |
| Always-on instructions      | Broken   | 51 KB `CLAUDE.md` with stale facts, contradictions and false enforcement claims ([App. A](#appendix-a-instruction-defects))                |
| Skills                      | Broken   | All six code-writing skills teach removed or banned patterns ([App. C](#appendix-c-skill-audit))                                           |
| Codex readiness             | Weak     | `AGENTS.md` is a pointer to the 51 KB file, states a different test-SQL rule, and no skills are visible to Codex                           |

## 3. Why agents became less effective

| #   | Cause                                         | Evidence                                                                                                                                                                                                                                                                                                                                                                             | Effect on agents                                                                                                                                                                                        |
| --- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R0  | Instructions only grow and nothing tests them | `CLAUDE.md` went from 2.9 KB (2025-12) to 14.4 KB (2026-05) to 51.5 KB (2026-09) over 69 commits. Item 8 of "When Making Changes" tells agents to update it. No size budget, owner or test exists.                                                                                                                                                                                   | Every incident adds a paragraph and none is removed. A one-off cleanup will rot again unless this changes.                                                                                              |
| R1  | Wrong guidance at the moment code is written  | `unit-test` builds on a base class removed in April. `htmx-form` teaches `onclick=`, which `CspTemplateComplianceTest` rejects. `ui-test` teaches calls that `UiTestHygieneTest` bans. `command-query` never mentions `Authorized`, which every message needs. `CLAUDE.md` contradicts itself on migration fold-back, the REST location and quality's tier.                          | Following instructions produces code that does not compile or fails guards, which means loops and corrections. Claude Code's docs warn that with contradictory rules "Claude may pick one arbitrarily". |
| R2  | Verification is slow, broken or undefined     | The documented `./gradlew test --tests X` fails in every module without a match (proven, [App. H](#appendix-h-reproducing-the-numbers)). Four different "run before committing" instructions. Guard tests need `pnpm build` plus `:apps:epistola`. The documented `bootRun` has started an app without demo data since #895. A fresh worktree needs `mise trust` before Gradle runs. | Agents skip, guess or overclaim verification. Sessions show repeated rounds of "still broken in the browser" after an agent reported done, and questions about whether a check was actually run.        |
| R3  | Rules and enforcement disagree                | `CLAUDE.md` says ktlint is "enforced in CI" and that contract alignment runs in the build lifecycle; CI runs neither. `UiRestApiSeparationTest` matches `/v1/`, which no route uses. 12 guard tests are never mentioned. Prose-only rules erode: 5 unparseable changelog entries, an ADR number collision a week after the docs cleanup (#887).                                      | Agents learn from wrong prose, and checks that never run do not stop them. Guards they are not told about surface only as surprise failures.                                                            |
| R4  | Context sinks dilute attention                | `CLAUDE.md` (51 KB) loads every session; about half of it matters only in one area and a fifth only for rare workflows. `CHANGELOG.md` is 738 KB, its `[Unreleased]` section alone 110 KB. 175 of 207 recent commits edited it, and sessions read it explicitly 64 times against 6 for `CLAUDE.md`.                                                                                  | Lower adherence (Claude Code's docs: longer files "reduce adherence") and earlier compaction. Real, but trimming alone will not help while R1 to R3 persist.                                            |
| R5  | Codex is not first-class                      | `AGENTS.md` (1.2 KB) points to `CLAUDE.md`, whose size is above Codex's default 32 KiB project-instruction cap if it were ever inlined. It names a heading that does not exist and states a different test-SQL rule. Codex discovers skills in `.agents/skills`, which does not exist here, and `.aiignore` hides `.agents/`.                                                        | Other agents start with less guidance, and what they get contradicts itself.                                                                                                                            |
| R6  | Two ways of doing many things                 | `onNonHtmx` vs `onFullPage`; `tenantId: TenantKey` (73) vs `tenantKey: TenantKey` (222); `require(` vs `validate(`; `mediator.send` in MCP vs `execute()` everywhere else; three backup package names; contract-generated code that cannot be grepped.                                                                                                                               | "Read existing code first" copies whichever neighbour turns up first, so the drift spreads.                                                                                                             |

Session data also shows project skills were invoked only 5 times in 81 sessions. Stale skills do
damage when they trigger, but most of the knowledge they hold simply never reaches the agent. The
always-on file and imitation of nearby code are the main channels.

## 4. Target instruction architecture

### 4.1 How the two agents load instructions

Verified against the vendors' documentation on 2026-09-11 ([App. G](#appendix-g-vendor-behaviour)).

| Behaviour          | Claude Code                                                                                                                  | Codex                                                                                                                                                                                 |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Root instructions  | Reads `CLAUDE.md`, not `AGENTS.md`. The documented pattern is a `CLAUDE.md` containing `@AGENTS.md`; imports load at launch. | Reads `AGENTS.override.md` or `AGENTS.md` in each directory from the project root to the working directory, concatenated at start, up to `project_doc_max_bytes` (32 KiB by default). |
| Subdirectory files | A nested `CLAUDE.md` loads on demand when Claude reads files in that directory                                               | Only directories between the project root and the working directory                                                                                                                   |
| Glob-scoped rules  | `.claude/rules/*.md` with `paths:` frontmatter load when a matching file is read; symlinks are supported                     | No equivalent; needs a routing table                                                                                                                                                  |
| Skills             | `.claude/skills/<name>/SKILL.md`                                                                                             | `.agents/skills/<name>/SKILL.md`, from the working directory up to the repository root                                                                                                |
| Hooks              | `.claude/settings.json`: SessionStart, PreToolUse, PostToolUse, PostToolBatch, Stop, InstructionsLoaded, …                   | `.codex/hooks.json`: SessionStart, PreToolUse, PostToolUse, Stop, UserPromptSubmit, …                                                                                                 |
| Size guidance      | "Target under 200 lines per CLAUDE.md file"                                                                                  | 32 KiB combined by default                                                                                                                                                            |

### 4.2 Principles

1. **One home per rule.** Other tools get an adapter (an import, a symlink or a one-line stub),
   never a copy.
2. **Enforce in the build first.** Prose is for what cannot be checked, and says `(unenforced)`.
3. **Route, don't preload.** Always-on text is what applies to every task, plus where to look for
   the rest.
4. **Point at exemplar files, not code snippets.** Real files compile and pass the guards;
   snippets rot.
5. **Test instruction files like code.**

### 4.3 Layout

```text
AGENTS.md                      canonical for every agent; ≤150 lines, ≤12 KB
CLAUDE.md                      "@AGENTS.md" plus Claude-only notes; ≤40 lines
<area>/AGENTS.md               area guide; ≤80 lines
<area>/CLAUDE.md               one line: @AGENTS.md
.agents/rules/<topic>.md       cross-cutting rule cards with paths: frontmatter; ≤60 lines
.claude/rules/<topic>.md       symlink to ../../.agents/rules/<topic>.md
.agents/skills/<name>/         canonical skills
.claude/skills/<name>          symlink to ../../.agents/skills/<name>
scripts/agent-hooks/           agent-neutral hook scripts (JSON in, JSON out)
changelog/unreleased/          changelog fragments (§8)
```

- **First area guides:** `modules/epistola-core`, core `catalog/` (including Exchange),
  `apps/epistola`, `modules/editor`, `modules/rest-api`, `charts`, `vulnerabilities`, `.github`
  and `docs`. Add others only when the same mistake happens twice.
- **First rule cards:**
  - `ui-markup` (`**/templates/**/*.html`, `**/static/**/*.js`)
  - `tests` (`**/src/test/**`)
  - `migrations` (`**/db/migration/**`)
  - `bundled-catalogs` (`**/catalogs/{system,demo}/**`)
  - `config-properties` (`**/*Properties.kt`, `**/application*.yaml`)
- **Codex budget:** a root file of at most 12 KB plus one area guide of at most 6 KB stays well
  under 32 KiB.

### 4.4 What the root `AGENTS.md` holds

| Section                                                                                                                                                     | Lines |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| Purpose and the post-GA stability contract (data stability, SemVer for GA surfaces, alpha/beta rule)                                                        | 12    |
| Repository map: Gradle project, role and guide, checked against `settings.gradle.kts`                                                                       | 25    |
| House idioms: `Command.execute()`/`Query.query()`, `EpistolaClock`, UI never calls `/api`, strict CSP, forward-only migrations, seed tests through commands | 15    |
| Verify loop: change type, then the fastest correct command, then the CI job that enforces it                                                                | 20    |
| Definition of done: format, targeted tests, changelog fragment, docs index, demo catalog, surfaces                                                          | 12    |
| Routing table (sample below)                                                                                                                                | 30    |
| Where contract-generated code lives (REST interfaces, template model, catalog protocol)                                                                     | 6     |
| Commits (the commitlint config is the vocabulary), secrets, vulnerability confidentiality                                                                   | 10    |

Sample routing table:

| When you touch                                | Read                              | Enforced by                                                                                                        |
| --------------------------------------------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `**/templates/**/*.html`, `**/static/**/*.js` | `.agents/rules/ui-markup.md`      | `CspTemplateComplianceTest`, `DesignSystemClassTest`, `IconUsageTest`, `PageHeaderUsageTest`, `InputMaxLengthTest` |
| `**/src/test/**`                              | `.agents/rules/tests.md`          | `UiTestHygieneTest`, raw-SQL ratchet (P2.3)                                                                        |
| `**/db/migration/**`                          | `.agents/rules/migrations.md`     | `checkMigrationVersions`, `SchemaHygieneAppTest`, `TenantTableTopologyDriftIntegrationTest`                        |
| Core commands, queries and handlers           | `modules/epistola-core/AGENTS.md` | `MediatorWiringTest`, `AuthorizationCoverageTest`, `DomainBoundaryTest`, `ApplicationClockUsageTest`               |

### 4.5 Keeping it from rotting

- **`pnpm agents:check` in CI**, modelled on `pnpm vulnerabilities:check`. It checks:
  - Referenced repository paths, Kotlin/TypeScript symbols, Gradle tasks and pnpm scripts exist.
  - Line and byte budgets per file, including the Codex chain.
  - Skill frontmatter: name matches its directory, description length, a `## Verify` section,
    exemplar paths exist, no code block longer than 15 lines.
  - The routing table matches the rule cards, symlinks match their targets, and every stub has its
    `AGENTS.md`.
  - Every guard test appears in some "Enforced by" cell.
  - Docs: every page indexed, ADR numbers unique, banner and index status agree, anchors resolve.
- **Admission policy.** Replace "update CLAUDE.md" in item 8 with: a new rule gets a guard if it
  can be mechanised. Otherwise it becomes one line marked `(unenforced)` in the owning guide or
  card. Edits to the root file state their reason.
- **Ownership.** Add CODEOWNERS entries for `AGENTS.md`, `CLAUDE.md`, `**/AGENTS.md` and
  `.agents/`.
- **Memory.** Auto memory holds preferences only. Project facts belong in the repository, where
  Codex sees them too, and branch status never goes into memory.
- **Verify while implementing:** that `paths:` is honoured through a symlinked rule; that a nested
  `CLAUDE.md` stub's `@AGENTS.md` import expands on lazy load; that Codex ignores Claude-only skill
  frontmatter keys.

## 5. Roadmap

Effort: **S** ≤ 2 hours, **M** ≤ 1 day, **L** 2–4 days.

### P0: stop the bleeding (about a day, no restructuring)

| ID   | What                                                                                                                                                                                                                                                                                                                             | Why                             | Effort | Cross-agent                                                  |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------- | ------ | ------------------------------------------------------------ |
| P0.1 | Remove `command-query`, `contract-change`, `editor-component`, `htmx-form`, `ui-test` and `unit-test`, and route their subjects to the maintained docs from a "Where to look" table instead. Their content is wrong, the docs are not, and git history keeps the text for the rewrites. Delete the duplicate `pr-review/mcp.md`. | R1                              | S      | The table serves every agent; the skills reached only Claude |
| P0.2 | Fix the statements in [App. A](#appendix-a-instruction-defects) in place: versions, migration fold-back (forbidden), REST location, quality's tier, the `execute()`/`query()` idiom, `bootRun` → `:apps:epistola-demo`, one test-cadence table, commit vocabulary. Align `AGENTS.md`'s test-SQL rule and heading.                | R1                              | M      | Both, since `AGENTS.md` defers to `CLAUDE.md`                |
| P0.3 | Add `ktlintCheck checkContractVersionAlignment` to the CI compile job and `pnpm lint:css` to frontend-checks. `ktlintCheck` passes on `main` in 22 seconds, so this is cheap.                                                                                                                                                    | R3                              | S      | Build: everyone                                              |
| P0.4 | Set `isFailOnNoMatchingTests = false` on the `test` task. Accept `type(scope)!:` in `ChangelogRenderer`, test it over the real `[Unreleased]` section, and repair the 5 breaking entries (two use the invalid `feat!(exchange):` form) and the `[perf]` badge.                                                                   | R2, R3                          | S      | Everyone; the in-app "Upcoming" list is user-visible         |
| P0.5 | Interim changelog guard: one `AGENTS.md` line ("never read `CHANGELOG.md` whole; read the first 12 lines and insert under `[Unreleased]`"), plus hook H2                                                                                                                                                                         | R4                              | S      | The line applies to all agents; the hook is Claude-only      |
| P0.6 | Prune stale auto-memory notes, and move durable development-loop knowledge into the repository                                                                                                                                                                                                                                   | R1, [App. A.4](#a4-auto-memory) | S      | Makes those facts visible to Codex                           |

### P1: restructure (about a week, one PR per row)

| ID   | What                                                                                                                                                                                                                                                                                                                                     | Why        | Effort | Cross-agent                                                           |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ------ | --------------------------------------------------------------------- |
| P1.1 | Canonical slim `AGENTS.md` plus an import-only `CLAUDE.md`, following [App. D](#appendix-d-claudemd-migration-map). Keep the `docs/README.md` link to `CLAUDE.md#commit-conventions` working.                                                                                                                                            | R0, R4, R5 | L      | Both agents load the same core                                        |
| P1.2 | Area guides with stubs, rule cards with symlinks, and the routing table                                                                                                                                                                                                                                                                  | R4         | L      | Codex through the routing table; Claude through lazy and glob loading |
| P1.3 | Move skills to `.agents/skills` (symlinked into `.claude/skills`); rewrite or retire them per [§7](#7-skills-portfolio); remove `.agents/` from `.aiignore`                                                                                                                                                                              | R1, R5     | L      | Codex gains skills                                                    |
| P1.4 | Changelog fragments ([§8](#8-changelog-fragments)); remove `CHANGELOG.md merge=union`                                                                                                                                                                                                                                                    | R4         | L      | Build-enforced                                                        |
| P1.5 | Docs cleanup ([App. E](#appendix-e-documentation-audit)): index the 3 unindexed pages, renumber the second ADR 0011, update the statuses of ADRs 0011–0013, fix `TenantId.of` in `docs/htmx.md` and `CoreIntegrationTestBase` in `docs/auth.md`, pick one canonical page per duplication cluster, document the root `plan.md` convention | R1         | M–L    | Everyone                                                              |

### P2: mechanical enforcement (every agent and every human)

| ID   | What                                                                                                                                                                                                                                                                                                                 | Why | Effort |
| ---- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --- | ------ |
| P2.1 | `pnpm agents:check` ([§4.5](#45-keeping-it-from-rotting))                                                                                                                                                                                                                                                            | R0  | M      |
| P2.2 | Repair guards. `UiRestApiSeparationTest`: match real `/api/` calls and scan every module plus the editor TypeScript. `UiTestHygieneTest`: cover every `@Tag("ui")` class. `ApplicationClockUsageTest`: add `System.currentTimeMillis()` and `Clock.systemUTC()`. Add a bundled-catalog `release.version` bump check. | R3  | M      |
| P2.3 | Ratchet tests on the drift counters in [App. F](#appendix-f-drift-counters): a count may fall but never rise, and the failure message names the preferred idiom                                                                                                                                                      | R6  | M      |
| P2.4 | Fast guard path: move the source-scanning guards to a light Gradle project that needs neither `pnpm build` nor Spring, runs under 60 seconds warm, and has one documented command                                                                                                                                    | R2  | M–L    |

### P3: Claude accelerators and skills (after P1 and P2, so hooks call real checks)

| ID   | What                                                                                                                             | Why    | Effort |
| ---- | -------------------------------------------------------------------------------------------------------------------------------- | ------ | ------ |
| P3.1 | Hooks H1–H5 ([§6](#6-hooks)). Scripts are agent-neutral, so Codex can wire the same ones later.                                  | R2     | M      |
| P3.2 | New skills `local-suite`, `catalog-resource-change`, `feature-module`, `vulnerability-record` ([§7](#7-skills-portfolio))        | R1, R2 | M      |
| P3.3 | Measurement: an InstructionsLoaded log plus a transcript metrics script, compared over 4-week windows ([§9](#9-success-metrics)) | –      | S–M    |

### P4: consistency backlog (ratcheted by P2.3, done opportunistically)

1. **UI rendering idiom:** 103 `onNonHtmx {}` to `onFullPage {}`, 41 raw
   `ServerResponse.ok().render(...)` calls, dead helpers (`onNonHtmxLegacy`, `NonHtmxBuilder`).
2. **Tenant field naming:** `val tenantId: TenantKey` (73) to `tenantKey`.
3. **CQRS edges:** `require(` in 24 handler files (throws `IllegalArgumentException`, not
   `ValidationException`); `@Transactional` on 11 handlers the mediator already wraps; 26
   `mediator.send/query` calls in MCP; files holding several messages.
4. **Packages:**
   - `apps/epistola/.../handlers/` is flat, with 23 files declaring other packages.
   - Packages are split across Gradle modules.
   - `exchange` is flat.
   - There are three backup package names.
   - The "upgrading" module is actually the compatibility check.
5. **Module graph:**
   - Feature modules are wired into `apps:epistola` by classpath only; add a contribution test.
   - Backups does not depend on snapshots, contrary to the docs.
   - Kover aggregation omits several modules.
   - Some dependency versions are hard-coded.
   - The `catalog.CatalogKey` compatibility typealias is still imported by 86 files.
6. **Single sources:**
   - The slug regex is repeated 43 times (22 Kotlin, 10 HTML, 11 SQL).
   - There are two SemVer types.
   - `StencilNodeKeys` is defined twice.
   - `UUID.randomUUID()` appears 10 times vs 22 `UUIDv7.generate()`.
7. **Tests:**
   - Integration test names mix `*IT` and `*IntegrationTest`.
   - `IntegrationTestBase` and `BaseIntegrationTest` have near-identical names.
   - Type-check the editor's `*.test.ts`.
   - 69 raw `INSERT INTO` statements sit in 34 test files.
8. **Editor and hotspots:**
   - Tag prefixes (`epistola-` 20, `ep-` 3, none 4).
   - Two state patterns: `EventEmitter` vs `extends EventTarget`.
   - Large files: `CatalogHandler.kt` (1,220), `EpistolaEditor.ts` (1,771), `registry.ts` (1,674).
9. **Host coupling:** feature-module templates use host-only fragments (`fragments/confirm-dialog`,
   `fragments/search`). Move them to `epistola-web` or document them as a host contract.
10. **Carried over** from the 2026-06-08 review: MCP is still enabled by default
    (`epistola.mcp.enabled:true`).

## 6. Hooks

These are Claude-only accelerators; the build stays the enforcer. Scripts live in
`scripts/agent-hooks/` so Codex's `.codex/hooks.json` can reuse them.

| #           | Event and filter                                 | Behaviour                                                                                                                                                                                                      | Budget        |
| ----------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| H1          | SessionStart (`startup\|resume\|clear\|compact`) | At most 12 lines of context: branch, merge-base with `origin/main`, dirty file count, whether the editor `dist` exists, whether `mise` trusts the worktree, whether the local suite is up (port probe)         | ≤ 300 ms      |
| H2          | PreToolUse on `Read` of `**/CHANGELOG.md`        | Blocks reads without a small `limit` (exit 2 with a hint)                                                                                                                                                      | ≤ 50 ms       |
| H3          | PreToolUse on `Edit\|Write`                      | Blocks edits to migrations that already exist on `origin/main`, to the generated `VULNERABILITIES.md`, and (after P1.4) to `CHANGELOG.md` outside a release. Blocks `git commit --no-verify`.                  | ≤ 150 ms      |
| H4          | PostToolBatch                                    | One `oxfmt --check` over the non-Kotlin files edited in the batch (about 0.5 s per file measured). Check only, never rewrite: rewriting under the agent causes stale-read errors (20 in the sessions studied). | ≤ 1.5 s       |
| H5          | Stop (skipped when `stop_hook_active`)           | If the tree changed, run cheap checks: fragment present, migration naming, new doc indexed, bundled catalog changed together with its `catalog.json`, formatting. Block once with a combined reason.           | ≤ 2 s         |
| H6 optional | Stop, `asyncRewake`                              | Background `ktlintCheck` for touched modules; wakes Claude only on failure                                                                                                                                     | 20–90 s async |
| H7 optional | InstructionsLoaded (local settings)              | Append which instruction files loaded and why to a JSONL log, for measurement                                                                                                                                  | ≤ 20 ms       |

Not recommended: a UserPromptSubmit router (skill descriptions and the routing table already do
this), or per-edit Kotlin formatting (ktlint runs only through Gradle today, and a standalone binary
would be one more version to keep aligned).

## 7. Skills portfolio

The six outdated skills were removed in P0.1 rather than patched, so each "rewrite" below starts
from current code and guard tests, not from the old text (which git history still holds). Until a
rewrite lands, the "Where to look" table in `CLAUDE.md` routes that area to its doc.

Every skill should follow one standard:

- At most 150 lines.
- A description of at most 300 characters, key use case first.
- Sections: When, Exemplars (real files), Steps, Verify.
- No code block longer than 15 lines.

| Skill                           | Action                       | Reason                                                                                     | New shape                                                                                                                             |
| ------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `command-query`                 | Rewrite                      | No authorization markers; old slug domains and migration path                              | Exemplars: `CreateEnvironment.kt`, one query, one `SystemInternal` message. Verify: `MediatorWiringTest`, `AuthorizationCoverageTest` |
| `unit-test`, `ui-test`          | Retire into the `tests` card | Removed base class; banned Playwright calls                                                | One exemplar per test type that passes `UiTestHygieneTest`; points to `docs/testing.md`                                               |
| `htmx-form`                     | Rewrite as `ui-page`         | Inline `onclick`; flow predates dialog forms (#758) and the `epistola-web` move (#502)     | Exemplars for a list page, a dialog form and a feature-module page; lists the markup guards                                           |
| `contract-change`               | Rewrite as `contract-bump`   | Error model predates RFC 9457 (#472); misses the atomic npm and lockfile bump              | Where generated interfaces live; the three-file bump; `checkContractVersionAlignment`                                                 |
| `editor-component`              | Rewrite as `document-block`  | Misses the main case and uses wrong paths                                                  | Contract-owned `examples[]`, registration, PDF `*NodeRenderer`, demo catalog plus fingerprint                                         |
| `pr-review`                     | Rewrite, thin                | About 90% restates `CLAUDE.md` and has drifted (pre-GA compatibility rule, inline scripts) | Establish the diff, then check it against `AGENTS.md`, the touched guides and cards, and their "Enforced by" guards; `context: fork`  |
| `release`, `release-helm-chart` | Keep, manual-only            | Side effects                                                                               | `disable-model-invocation: true`; release calls `releaseChangelog`                                                                    |
| `debug-epistola-templates`      | Keep, move                   | Accurate and already portable                                                              | Move to `.agents/skills`                                                                                                              |
| `local-suite`                   | Add                          | Verification friction (R2)                                                                 | Start, stop and reset the demo app; the `DemoLoader` API key; `scripts/mcp-smoke.sh`; a browser check. "Done" means seen working.     |
| `catalog-resource-change`       | Add                          | 28 commits in core `catalog/`, 17 in the demo catalog, 16 fingerprint bumps                | `ImportTemplates` (insert and both update paths), `CatalogContentBuilder`, contract model, round-trip test, version plus fingerprint  |
| `feature-module`                | Add                          | 38 build-file and 12 feature-toggle commits                                                | Settings include, Kover, explicit wiring, migrations folder, toggle maturity and tier, nav contributor                                |
| `vulnerability-record`          | Add, manual-only             | 5.8 KB of `CLAUDE.md` today                                                                | The private-advisory workflow; record-format rules go in `vulnerabilities/AGENTS.md`                                                  |

Frequent work that gets a rule card or area guide instead of a skill: changelog (§8), migrations,
Helm (`charts/AGENTS.md`), CI workflows (`.github/AGENTS.md`), configuration properties, static JS,
the renderer and MCP.

## 8. Changelog fragments

**Why.**

- Since 2026-06-11, 175 of 207 commits by people edited `CHANGELOG.md`, every one inserting at line 7.
- `.gitattributes` sets `CHANGELOG.md merge=union`, so concurrent entries are silently unioned
  rather than conflicting.
- `[Unreleased]` holds 188 entries averaging 574 characters (p90 911, max 2,368).
- The in-app parser rejects all 5 breaking-change entries: `ChangelogRenderer` has no `!` in its
  pattern, and its test skips `[Unreleased]`.

**Layout.** `changelog/README.md` holds the format and is the single source; `CONTRIBUTING.md` and
`AGENTS.md` link to it. Each change adds `changelog/unreleased/<yyyymmddHHMMSS>-<slug>.md`, named
with the same `date -u` generator as migrations. That keeps names unique across branches, ordered,
and free of conflicts. `CHANGELOG.md` keeps the released history only.

**Fragment format:**

```markdown
---
type: feat # required: feat, fix, perf, refactor, docs, test, build, ci, chore
scopes: [catalog, exchange] # required, kebab-case
audience: user # optional: user or dev; omitted means everyone
breaking: true # optional; replaces "!"
maturity: beta # optional: alpha or beta, for breaking changes to non-GA features
title: A catalog installs and upgrades as one unit.
issues: [927] # optional
---

One paragraph for the in-app reader. The check warns above 900 characters.
```

**Validator.** A buildSrc `CheckChangelogFragmentsTask`, following the precedent of
`CheckMigrationVersionsTask`, runs in the CI compile step next to `checkMigrationVersions`. It
reuses that task's base-ref inputs to fail a PR that changes `*/src/main/**` or `charts/**` without
adding a fragment, unless the PR carries a `no-changelog` label.

**In-app changelog.** A `renderChangelog` task writes the combined Markdown (an `[Unreleased]`
section rendered from fragments in today's `- **[audience]** type(scope)!: **Title.** body` form,
then the released history). `processResources` in `apps/epistola/build.gradle.kts` copies that
file instead of the root `CHANGELOG.md`. `ChangelogRenderer` needs only the `!` fix. A test renders
the generated file with `includeUnreleased = true` and asserts every entry has a type and scope, so
renderer and validator cannot drift apart.

**Release.** `./gradlew releaseChangelog -PreleaseVersion=X.Y.Z` writes the dated section (breaking
changes first, then by type, scope and timestamp) and removes the fragments. The `release` skill
calls it before writing the summary.

**Migration:**

1. Fix `!` parsing.
2. Add the task and `changelog/README.md`.
3. Convert the current `[Unreleased]` bullets once with a throwaway script.
4. Switch `processResources`.
5. Remove `merge=union`.
6. Update `AGENTS.md`, `CONTRIBUTING.md`, the PR template and the `release` skill.

The Helm chart changelog is out of scope; the app does not show it.

## 9. Success metrics

Re-measure at each release. Session metrics come from local transcripts; compare rates per session
over matching windows and note model changes.

| Metric                                                                      | Baseline (2026-09-11)                                  | Target                            |
| --------------------------------------------------------------------------- | ------------------------------------------------------ | --------------------------------- |
| Always-on instruction bytes                                                 | Claude 51 KB; Codex 1.2 KB pointer to 51 KB            | ≤ 12 KB each; Codex chain ≤ 24 KB |
| Instruction defects found by `agents:check`                                 | ≥ 25 known ([App. A](#appendix-a-instruction-defects)) | 0, CI-gated                       |
| Skills that are correct / visible to Codex                                  | 3 of 10 / 0                                            | All / all                         |
| Checks the docs claim but CI does not run                                   | 3                                                      | 0                                 |
| Guard tests never mentioned to agents / guards that cannot fire             | 12 / 1                                                 | 0 / 0                             |
| Commits by people editing `CHANGELOG.md` (since 2026-06-11)                 | 175 of 207                                             | 0                                 |
| `[Unreleased]` entries the in-app parser misreads                           | 5 (plus 1 invalid badge)                               | 0                                 |
| Drift counters ([App. F](#appendix-f-drift-counters))                       | As listed                                              | Never increase                    |
| Docs: unindexed pages / ADR collisions / broken anchors / status mismatches | 3 / 1 / 2 / 4                                          | 0                                 |
| Explicit `CHANGELOG.md` reads per session                                   | 0.8 (64 in 81 sessions)                                | < 0.05                            |
| Edit-before-read / stale-read tool errors                                   | 62 / 20 in 81 sessions                                 | Falling                           |
| Project skill invocations per session                                       | 0.06 (5 in 81)                                         | Rising, once skills are correct   |
| Agent PRs failing CI on first push; user corrections per session            | Not measured                                           | Falling                           |

## 10. Decisions needed

1. ~~**AI attribution.**~~ **Settled 2026-09-12:** attribution may stay. `CLAUDE.md` no longer bans
   references to AI in commit messages, so the trailers the tooling adds — present on 51 of the last
   306 commits — are the documented behaviour rather than a standing contradiction.
2. **Adapters:** symlinks (recommended) or import and stub files. Symlinks need Developer Mode on
   Windows.
3. **Fragment tooling:** buildSrc Kotlin (recommended, single renderer) or Python (the
   vulnerability-tooling precedent).
4. **Codex hooks:** wire the same scripts into `.codex/hooks.json` now, or later.
5. **Fragment body length:** warning only, or a hard cap.
6. **Area-guide ownership:** who approves changes to `AGENTS.md` files (CODEOWNERS).

## Appendix A. Instruction defects

Line numbers refer to `CLAUDE.md` at `aa063b251`.

### A.1 Stale or wrong facts

| Where         | Says                                                                   | Actually                                                                                                                                                                                                                                         |
| ------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| L7            | Spring Boot 4.0.0, Kotlin 2.3.0                                        | 4.1.1 and 2.3.21 (`gradle/libs.versions.toml`)                                                                                                                                                                                                   |
| L27–60        | Project tree rooted at `epistola-suite-modules/`                       | Project is `epistola-suite`. The tree shows 8 of 21 Gradle projects; missing `apps/pdfrender`, `epistola-crypto`, `epistola-web`, `epistola-version-check`, `epistola-quality`, `epistola-audit`, `loadtest` and the `epistola-support*` modules |
| L33, L35, L37 | `apps/epistola` has `htmx/`, `db/migration/`, `application.yml`        | HTMX DSL is in `modules/epistola-web`; migrations are module-owned; the file is `application.yaml`                                                                                                                                               |
| L52, L72      | `epistola-core/api/` holds REST controllers                            | No such package; controllers are in `modules/rest-api`                                                                                                                                                                                           |
| L104          | Commercial per-feature modules include `epistola-support-quality`      | No such module; quality is the OSS module `epistola-quality` (as L109 says)                                                                                                                                                                      |
| L106          | Backups and Upgrading depend on the snapshots module                   | Only Upgrading does; Backups has its own `tenantbackup` primitive (as L107 says)                                                                                                                                                                 |
| L131          | Extension-point SPIs: "Two exist"                                      | Four: `NavContributor`, `FooterContributor`, `HomeNoticeContributor`, `FragmentModelContributor`                                                                                                                                                 |
| L179          | Prefer `MediatorContext.current()` / `.send()` / `.query()`            | No such methods. The idiom is `Command.execute()` / `Query.query()` (`mediator/MediatorExtensions.kt`), used at about 686 call sites and never mentioned                                                                                         |
| L229, L529    | Component `examples[]` are edited in `registry.ts`                     | The contract owns the vocabulary and examples (`docs/component-registry.md`)                                                                                                                                                                     |
| L260          | `./gradlew test --tests UiRestApiSeparationTest`                       | Fails from the root: `test` fails on modules without a match ([App. H](#appendix-h-reproducing-the-numbers))                                                                                                                                     |
| L282, L335    | `./gradlew :apps:epistola:bootRun`                                     | Has had no demo tenant or key since #895; local agent verification needs `:apps:epistola-demo:bootRun` (`docs/auth.md`)                                                                                                                          |
| L323          | CycloneDX 3.3.0                                                        | 3.4.1                                                                                                                                                                                                                                            |
| L437          | Tables with no command: `consumer_nodes`, `consumer_partition_cursors` | `consumer_nodes` does not exist; `consumer_partition_cursors` is written by the `AcknowledgeGenerationResults` command                                                                                                                           |
| L463          | `./gradlew test` runs "all" tests                                      | `test` excludes the `ui` and `perf` tags                                                                                                                                                                                                         |
| L569          | Use the GitHub MCP tools for issues and PRs                            | The skills use the `gh` CLI; the MCP server is optional local setup                                                                                                                                                                              |

### A.2 Contradictions

| Topic               | One place says                                                                           | Another says                                                                                                    |
| ------------------- | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Migration fold-back | L122–124: folding `ALTER`s into the `CREATE` is a deliberate consolidation               | L16 and `docs/migrations.md`: no more rewrites; `CheckMigrationVersionsTask` rejects modified merged migrations |
| Quality tier        | L104: commercial                                                                         | L109: OSS, deliberately outside the support tier                                                                |
| REST location       | L52, L72, L528: `epistola-core/api`                                                      | L54, L101: `modules/rest-api`                                                                                   |
| Test SQL            | L436–440: raw SQL allowed when no command can produce the state                          | `AGENTS.md`: no direct SQL for test setup unless the test's subject is the database                             |
| Test cadence        | L405: `unitTest integrationTest` before committing                                       | L508: `./gradlew test` before and after changes; L463: `test` before a PR; L525: small commits                  |
| Formatting order    | L346: format before committing                                                           | L347: run `format:check` after committing                                                                       |
| Commit types        | L375–383: 7 types                                                                        | Changelog rules: 9 types; `.husky/commitlint.config.js`: 11                                                     |
| Version numbers     | L555: never change them manually                                                         | Catalog `release.version` bumps and the release process are manual                                              |
| AI references       | L393: never reference Claude or AI in commits (settled 2026-09-12: attribution may stay) | Co-author trailers on 51 of the last 306 commits                                                                |
| `AGENTS.md` pointer | Points to a "Vulnerability records" item                                                 | The item is titled "Handle vulnerabilities privately; publish repository-owned records"                         |

### A.3 Enforcement claims that do not hold

| Claim                                                                                      | Reality                                                                                                                                 |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| L352: ktlint "enforced in CI"                                                              | No workflow runs `ktlintCheck`; CI stopped running `gradle build` in 269ed1395 (2026-05-15). It currently passes locally.               |
| L318–320: `checkContractVersionAlignment` is wired into `check`/`build` and fails on drift | True locally, but CI never runs `check` or `build`                                                                                      |
| L389: commit messages are validated by commitlint                                          | Only by a local husky hook, installed by `scripts/init.sh`; no CI check                                                                 |
| L258–261: `UiRestApiSeparationTest` verifies UI and REST separation                        | Its path pattern matches only `/v1/`; every REST controller is mounted at `@RequestMapping("/api")` ([App. B](#appendix-b-guard-audit)) |

### A.4 Auto memory

Claude Code's auto memory is shared by every worktree of the repository and loaded each session.

- **Stale branch notes:** two describe branches as unpushed and parked; both merged (#869, #915).
- **Stale GA note:** one says API breaks are allowed "until GA", which shipped 2026-07-31.
- **Stale seam:** one points to `toValidationErrorResponse()`, removed with the RFC 9457 work (#472).
- **Likely stale:** two more are framed around "before GA".
- **Orphaned directory:** a per-worktree memory directory predates shared memory and holds a
  local-suite development-loop note that no session loads any more.

### A.5 Where `CLAUDE.md`'s bytes go

| Share | Bytes (approx.) | Content                                                                                                                  |
| ----- | --------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 29%   | 14.9 KB         | Relevant to nearly every session: overview, structure, UI vs REST, build commands, code style, commits, testing basics   |
| 50%   | 25.7 KB         | Relevant only in one area: catalog and Exchange, support-tier modules, quality, extension points, clock, CSP, test rules |
| 21%   | 10.8 KB         | Rare workflows: vulnerability handling (5.8 KB), contract bumps, concurrency scripts, GitHub MCP setup                   |

## Appendix B. Guard audit

### B.1 Guards weaker than they look

| Guard                       | Gap                                                                                                                                                                                        |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `UiRestApiSeparationTest`   | Pattern `['"/](api/)?v1/` cannot match `/api/tenants/...` (checked with the same regex). It scans only `apps/epistola`, not the 7 feature modules with templates or the editor TypeScript. |
| `UiTestHygieneTest`         | Scans only the `ui/` test package; `@Tag("ui")` classes elsewhere are unchecked                                                                                                            |
| `ApplicationClockUsageTest` | Does not look at `System.currentTimeMillis()` (8 uses in main, for example `JobPoller`) or `Clock.systemUTC()` (7, some legitimate in `generation`)                                        |
| `ChangelogRendererTest`     | Parses only released sections, so the 5 unparseable `[Unreleased]` entries pass                                                                                                            |
| Architecture tests overall  | One ArchUnit test in the repository (`CatalogExchangeIndependenceTest`); module layering is otherwise enforced only by compilation                                                         |

### B.2 Rules and their enforcement

| Rule                                                                             | Enforced by                                                       |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| No bare `now()` in application code                                              | `ApplicationClockUsageTest` (gaps above)                          |
| Migration versions unique, ordered after the base branch, merged files unchanged | `checkMigrationVersions` (CI compile job)                         |
| No inline scripts or `on*=` handlers                                             | `CspTemplateComplianceTest`; browser CSP violations fail UI tests |
| One handler per message; authorization declared                                  | `MediatorWiringTest`, `AuthorizationCoverageTest`                 |
| Catalog does not depend on Exchange                                              | `CatalogExchangeIndependenceTest`                                 |
| Bundled catalog fingerprints regenerated                                         | `DemoCatalogFingerprintTest`, `BundledCatalogFingerprintTest`     |
| Editor components have `examples[]`                                              | `registry-examples.test.ts`, `check-component-registry.mjs`       |
| Kotlin warnings fail the build                                                   | `allWarningsAsErrors`, `org.gradle.warning.mode=fail`             |
| Formatting, oxlint, license headers, secrets                                     | CI frontend-checks job, gitleaks workflow and pre-commit hook     |
| ktlint; contract version alignment; stylelint                                    | **Not in CI** (local `check` only; stylelint nowhere)             |
| Changelog format; catalog `release.version` bump; demo-catalog coverage          | **Prose only**                                                    |
| Docs and ADRs indexed; feature-toggle reads through queries                      | **Prose only**                                                    |
| Seed test state through commands                                                 | **Prose only** (69 `INSERT INTO` in 34 test files)                |

### B.3 Guards agents are never told about

`CLAUDE.md` names none of these tests, although each fails the build on a rule it describes:

`MediatorWiringTest` · `AuthorizationCoverageTest` · `DomainBoundaryTest` ·
`ApplicationClockUsageTest` · `InputMaxLengthTest` · `DesignSystemClassTest` · `IconUsageTest` ·
`PageHeaderUsageTest` · `SchemaHygieneAppTest` · `TenantTableTopologyDriftIntegrationTest` ·
`MovableResourceGuardTest` · `NameLengthValidationTest`

## Appendix C. Skill audit

| Skill                      | Last changed | State        | Main defects                                                                                                                                                 |
| -------------------------- | ------------ | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `command-query`            | 2026-03-29   | Broken       | No `RequiresPermission`, `SystemInternal` or other `Authorized` marker (required since #237); slug-era SQL domains; migration path under `apps/epistola`     |
| `contract-change`          | 2026-03-29   | Broken       | Teaches `ApiErrorResponse`, which no longer exists (RFC 9457 since #472); omits the npm and lockfile bump and `checkContractVersionAlignment`                |
| `editor-component`         | 2026-03-29   | Broken       | Wrong paths (`theme-editor/theme-editor-lib.ts`, `src/main/resources/static/css/`); misses document blocks, `examples[]`, PDF renderers and the demo catalog |
| `htmx-form`                | 2026-07-09   | Broken       | `onclick="openConfirmDialog(this)"` violates the strict CSP (#691); `HtmxDsl.kt` placed in `apps/epistola` (moved in #502); predates dialog forms (#758)     |
| `ui-test`                  | 2026-05-11   | Broken       | Teaches `page.navigate(...)` and `waitForSelector("…[open]")`, both banned by `UiTestHygieneTest` (#422); outdated `CreateDocumentTemplate` constructor      |
| `unit-test`                | 2026-03-29   | Broken       | Builds on `CoreIntegrationTestBase` (removed in #277, 2026-04-03); old scenario DSL                                                                          |
| `pr-review`                | 2026-09-03   | Drifted      | Pre-GA compatibility rule; the removed `toValidationErrorResponse()` seam; recommends inline `<script>`; checks only the system catalog fingerprint          |
| `release`                  | 2026-07-17   | Mostly right | Copy-pasted "chart changes are merged" prerequisite                                                                                                          |
| `release-helm-chart`       | 2026-07-05   | Accurate     | –                                                                                                                                                            |
| `debug-epistola-templates` | 2026-07-30   | Accurate     | Also ships Codex UI metadata (`agents/openai.yaml`)                                                                                                          |

The 2026-03-29 dates are a repository-wide formatting pass; those four skills last changed in
substance in February. Since then about eleven architectural changes landed that each invalidated
at least one skill:

- composite IDs (#197)
- authorization markers (#237)
- the shared testing module (#277)
- UI test hygiene (#422)
- RFC 9457 errors (#472)
- the `epistola-web` extraction (#502)
- strict CSP (#691)
- dialog forms (#758)
- GA
- the demo split (#895)

`.claude/skills/pr-review/mcp.md` is a byte-identical copy of the debug skill's reference file and
is referenced nowhere.

**Commits by people since 2026-06-11, grouped by the files they touched:**

| Area                         | Commits | Area                          | Commits |
| ---------------------------- | ------- | ----------------------------- | ------- |
| `CHANGELOG.md`               | 175     | Flyway migrations             | 23      |
| `docs/`                      | 85      | `rest-api`                    | 22      |
| Thymeleaf templates          | 62      | `application*.yaml`           | 21      |
| `apps/epistola` handlers     | 50      | Static JS                     | 21      |
| Gradle build files, buildSrc | 38      | Demo catalog                  | 17      |
| Editor TypeScript            | 33      | Bundled `catalog.json`        | 16      |
| Core `catalog/` package      | 28      | Generation renderer           | 13      |
| Helm charts                  | 26      | Feature toggles (`features/`) | 12      |
| `CLAUDE.md`                  | 23      | `epistola-mcp`                | 11      |
| CI workflows                 | 14      | `.claude/skills/`             | 9       |

## Appendix D. `CLAUDE.md` migration map

| `CLAUDE.md` section (lines)                                      | New home                                                                                                                             |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Project overview, release status (3–23)                          | Root `AGENTS.md`: purpose and stability contract                                                                                     |
| Project structure tree (24–62)                                   | Root `AGENTS.md`: repository map table, checked by `agents:check`                                                                    |
| Module responsibilities: short descriptions (63–72, 100–111)     | Root map, one line per module                                                                                                        |
| Exchange integration, resource references (73–99)                | `modules/epistola-core/.../catalog/AGENTS.md`; rationale stays in the linked docs                                                    |
| Support, feedback, snapshots, backups, upgrading (104–108)       | `modules/epistola-support*/AGENTS.md` (one short guide each or a shared one)                                                         |
| Migrations are module-owned (113–125)                            | `migrations` rule card                                                                                                               |
| Commercial-tier architecture, extension points (126–136)         | `modules/epistola-web/AGENTS.md`                                                                                                     |
| Quality ledger (137–138)                                         | `modules/epistola-quality/AGENTS.md`                                                                                                 |
| Feature-toggle reads (139–140)                                   | Core guide, plus a guard if feasible                                                                                                 |
| Application time and mediator context (141–192)                  | Core guide: three lines plus a link to `docs/clock.md`                                                                               |
| CSP (201–226)                                                    | `ui-markup` rule card                                                                                                                |
| Editor component registrations (227–230)                         | `modules/editor/AGENTS.md` (corrected: the contract owns examples)                                                                   |
| UI handlers vs REST API (231–262)                                | Root house idioms (two lines) plus the repaired guard                                                                                |
| Build commands, development workflow (263–307, 329–340)          | Root verify-loop table; details in `README.md`                                                                                       |
| Contract dependency alignment (308–328)                          | `contract-bump` skill                                                                                                                |
| Code style, commit conventions, JSON (341–400)                   | Root: formatting commands and commit rules (commitlint config is the vocabulary); feature-maturity rule to `docs/feature-toggles.md` |
| Testing, shared testing module, seeding, UI test rules (401–464) | `tests` rule card plus `docs/testing.md`                                                                                             |
| Multi-instance and concurrency scripts (465–493)                 | `docs/cluster-resilience.md`; one routing-table row                                                                                  |
| Key files, don'ts (494–504, 550–556)                             | Delete (derivable), or fold into the definition of done                                                                              |
| When making changes, items 1–9 (505–525)                         | Root definition of done                                                                                                              |
| Item 10, bundled catalog releases (526)                          | `bundled-catalogs` rule card                                                                                                         |
| Items 11–14, catalog impact, surfaces, demo catalog (527–530)    | Root definition of done (one line each) plus the `catalog-resource-change` skill                                                     |
| Items 15–16, fonts and locale (531–532)                          | Core guide rows pointing to `docs/fonts.md` and `docs/locale.md`                                                                     |
| Item 17, vulnerabilities (533–548)                               | `vulnerabilities/AGENTS.md` plus the manual-only `vulnerability-record` skill                                                        |
| GitHub integration, MCP (557–581)                                | Delete; setup is in `README.md`                                                                                                      |

## Appendix E. Documentation audit

**Scale.** 129 Markdown files, 31,950 lines. The index added in #887 works; the gaps below are
regressions from a single later PR (#869, 264 files, +11,956 lines).

| Finding                              | Detail                                                                                                                                                      |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unindexed pages                      | `catalog-resource-identity-migration.md`, `catalog-resource-relocation.md`, `adr/0011-saas-to-client-bridge.md`                                             |
| ADR number collision                 | Two ADRs numbered 0011                                                                                                                                      |
| Stale decision status                | ADRs 0011 (quality input model), 0012 and 0013 still read "Draft — discussion record, not accepted" although the quality ledger shipped                     |
| Index and banner disagree            | `epistola.md`, `stencil-placeholders.md`, `stencil-parameters.md` (indexed Current, bannered); `catalog-exchange-publication.md` (indexed Alpha, no banner) |
| Broken anchors                       | `catalog-exchange-installation.md` to `catalog-exchange-publication.md#enrollment`; `collect-performance.md` to `../README.md#planning`                     |
| Code references that no longer exist | `TenantId.of` in `htmx.md` (L522, L543); `CoreIntegrationTestBase` in `auth.md` (L608)                                                                      |

**Duplication clusters.** Five subjects are each described in five or more places. Pick one
canonical page and mark the rest "superseded by":

- **Cluster runtime:** `horizontal-scaling-phase1.md`, `cluster-resilience.md`, `timers.md`, the
  v04/v05 collect proposals, and `CLAUDE.md`.
- **Eventing:** `eventing.md`, `minimal-eventing.md`, `plans/architecture.md`, ADRs 0009 and 0016.
- **HTMX and UI:** `htmx.md` (916 lines, with proposal-style sections), `dialog-forms.md`, ADR
  0010, the `htmx-form` skill (since removed), `CLAUDE.md`.
- **Testing:** `testing.md` (the accurate one), `testability-improvements.md`, two skills,
  `CLAUDE.md`.
- **Catalogs:** about 25 pages under `exchange/`, `catalog-*.md` and ADRs 0014, 0020 and 0022, plus
  8.9 KB of `CLAUDE.md`.

**Other.**

- The visual styleguide (`brandguide.md`, 1,753 lines) is linked from no skill, although
  `DesignSystemClassTest` exists because agents invented class names.
- The gitignored root `plan.md` is the de facto planning convention since OpenSpec was removed
  (#427), but it is documented nowhere.

## Appendix F. Drift counters

Baselines at `aa063b251`, for ratchet tests (P2.3). Counts are from the commands in Appendix H.

| Counter                                                                 | Count         | Preferred                               |
| ----------------------------------------------------------------------- | ------------- | --------------------------------------- |
| `onNonHtmx {}` call sites (outside `epistola-web`)                      | 103           | `onFullPage {}`                         |
| Raw `ServerResponse.ok().render(...)`                                   | 41            | `page()` / `htmx { fragment() }`        |
| `val tenantId: TenantKey` (main)                                        | 73            | `val tenantKey: TenantKey` (222)        |
| Handler files using `require(`                                          | 24            | `validate(...)` with a `ValidationCode` |
| Handler files annotated `@Transactional`                                | 11            | The mediator's transaction              |
| `mediator.send/query` in `epistola-mcp`                                 | 26            | `execute()` / `query()` extensions      |
| `apps/epistola/.../handlers/*.kt` declaring another package             | 23 of 69      | Directory matches package               |
| `INSERT INTO` in test sources                                           | 69 (34 files) | Commands or the fixture DSL             |
| `UUID.randomUUID()` in main                                             | 10            | `UUIDv7.generate()` (22)                |
| Slug regex literals (Kotlin / HTML / SQL)                               | 22 / 10 / 11  | One shared definition per layer         |
| Files importing the `catalog.CatalogKey` typealias                      | 86            | `common.ids.CatalogKey`                 |
| `System.currentTimeMillis()` in main                                    | 8             | `EpistolaClock`                         |
| `Pagination.paginate(` call lines in `rest-api`                         | 13            | Database `LIMIT`/`OFFSET`               |
| Kotlin main files over 1,000 lines / editor TypeScript files over 1,000 | 1 / 5         | Do not grow                             |

## Appendix G. Vendor behaviour

Read from the vendors' pages on 2026-09-11. Behaviour changes quickly, so re-check before
implementing.

| Fact                                                                                                                                                                                                                                                                     | Source                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------ |
| Claude Code reads `CLAUDE.md`, not `AGENTS.md`; recommended: a `CLAUDE.md` that imports `@AGENTS.md`                                                                                                                                                                     | [Claude Code: memory](https://code.claude.com/docs/en/memory)            |
| Imports load at launch, up to four hops; nested `CLAUDE.md` files load on demand                                                                                                                                                                                         | [Claude Code: memory](https://code.claude.com/docs/en/memory)            |
| `.claude/rules/` is discovered recursively; `paths:` scopes loading; symlinks supported                                                                                                                                                                                  | [Claude Code: memory](https://code.claude.com/docs/en/memory)            |
| "Target under 200 lines per CLAUDE.md file"; contradictory rules may be picked "arbitrarily"                                                                                                                                                                             | [Claude Code: memory](https://code.claude.com/docs/en/memory)            |
| Auto memory is shared by all worktrees of a repository; `MEMORY.md` loads up to 200 lines or 25 KB                                                                                                                                                                       | [Claude Code: memory](https://code.claude.com/docs/en/memory)            |
| Skill frontmatter includes `when_to_use`, `disable-model-invocation`, `paths`, `context: fork`; description plus `when_to_use` capped at 1,536 characters in the listing                                                                                                 | [Claude Code: skills](https://code.claude.com/docs/en/skills)            |
| Hooks: SessionStart matchers `startup`, `resume`, `clear`, `compact`, `fork`; `PostToolBatch` fires once per batch; Stop receives `stop_hook_active` and is overridden after 8 consecutive blocks; `asyncRewake`; `if` filters; InstructionsLoaded is observability-only | [Claude Code: hooks](https://code.claude.com/docs/en/hooks)              |
| Codex reads `AGENTS.override.md` or `AGENTS.md` per directory from project root to working directory, concatenated, capped by `project_doc_max_bytes` (32 KiB default)                                                                                                   | [Codex: AGENTS.md](https://developers.openai.com/codex/guides/agents-md) |
| Codex scans `.agents/skills` from the working directory up to the repository root                                                                                                                                                                                        | [Codex: skills](https://developers.openai.com/codex/skills)              |
| Codex hooks load from `<repo>/.codex/hooks.json` with SessionStart, PreToolUse, PostToolUse, Stop, UserPromptSubmit and others                                                                                                                                           | [Codex: hooks](https://developers.openai.com/codex/hooks)                |
| **Verify:** whether Codex models consult `AGENTS.md` files below the working directory on their own; whether Codex ignores Claude-only skill frontmatter; `paths:` through symlinked rules                                                                               | –                                                                        |

## Appendix H. Reproducing the numbers

Run from the repository root at the pinned commit. `MAIN='apps/*/src/main modules/*/src/main'`.

**Instruction surface:**

```bash
wc -lc CLAUDE.md AGENTS.md
git log --oneline -- CLAUDE.md | wc -l
git log --reverse --format='%h %ad' --date=format:%Y-%m -- CLAUDE.md | awk '!seen[$2]++'  # then: git show <h>:CLAUDE.md | wc -c
grep -c '^include(' settings.gradle.kts
```

**Changelog:**

```bash
wc -lc CHANGELOG.md
git log --no-merges --since=2026-06-11 --oneline | wc -l
git log --no-merges --since=2026-06-11 --oneline -- CHANGELOG.md | wc -l
grep -n CHANGELOG .gitattributes
# Entries rejected by the renderer: apply the audiencePattern and commitPattern
# from ChangelogRenderer.kt to each [Unreleased] bullet.
```

**CI and guards:**

```bash
grep -n 'run: gradle' .github/workflows/build.yml
grep -rnE 'ktlint|checkContractVersionAlignment|lint:css' .github/workflows | wc -l      # 0
./gradlew ktlintCheck --continue                                                         # BUILD SUCCESSFUL in 22s
./gradlew :modules:generation:unitTest --tests DoesNotExistXyz                           # BUILD SUCCESSFUL
./gradlew :modules:generation:test --tests DoesNotExistXyz                               # "No tests found for given includes"
grep -rhoE '@RequestMapping\("[^"]*"\)' modules/rest-api/src/main/kotlin | sort | uniq -c # 11 × "/api"
python3 -c "import re; p=re.compile(r'''['\"/](api/)?v1/'''); print(bool(p.search('hx-get=\"/api/tenants/acme/templates\"')))"  # False
grep -rn 'INSERT INTO' --include='*.kt' apps/*/src/test modules/*/src/test | wc -l
```

**Skills:**

```bash
# The six skills below were removed by the change this review accompanies (P0.1);
# run these at aa063b251 or earlier to reproduce the counts.
grep -c 'CoreIntegrationTestBase' .claude/skills/unit-test/SKILL.md                      # 5
grep -c 'onclick=' .claude/skills/htmx-form/SKILL.md                                     # 1
grep -cE 'page\.navigate|waitForSelector\(".*\[open\]' .claude/skills/ui-test/SKILL.md   # 3
grep -cE 'RequiresPermission|Authorized|SystemInternal' .claude/skills/command-query/SKILL.md  # 0
for s in .claude/skills/*/SKILL.md; do git log -1 --format="%ad $s" --date=short -- "$s"; done
```

**Code consistency:**

```bash
grep -rhoE ':\s*(CommandHandler|QueryHandler)<' --include='*.kt' $MAIN | wc -l
grep -h '^package ' apps/epistola/src/main/kotlin/app/epistola/suite/handlers/*.kt | sort | uniq -c
grep -rn 'onNonHtmx\s*{' --include='*.kt' $MAIN | grep -v modules/epistola-web/ | wc -l
grep -rn 'ok()\.render(' --include='*.kt' $MAIN | wc -l
grep -rn 'val tenantId: TenantKey' --include='*.kt' $MAIN | wc -l
grep -rlE ':\s*(CommandHandler|QueryHandler)<' --include='*.kt' $MAIN | xargs grep -l 'require(' | wc -l
grep -rnE 'mediator\.(send|query)\(' --include='*.kt' modules/epistola-mcp/src/main | wc -l
find $MAIN -name '*.kt' -exec wc -l {} + | awk '$2!="total" && $1>1000'
```

**Docs:**

```bash
find docs -name '*.md' | wc -l
ls docs/adr | grep -oE '^[0-9]{4}' | sort | uniq -d
# Unindexed pages: every docs/**/*.md not linked from a README.md index.
# Anchors: resolve each relative link's #fragment against the target's headings.
```

**Session metrics** come from local Claude Code transcripts (`~/.claude/projects/<project>/*.jsonl`):
count `Read` tool calls per file, `Skill` tool calls, and tool results flagged `is_error`, grouped by
message. They are not stored in the repository.
