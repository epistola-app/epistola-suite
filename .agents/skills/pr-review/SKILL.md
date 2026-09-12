---
name: pr-review
description: Review a branch or PR against epistola's conventions, architecture and altitude. Use when reviewing changes; complements /code-review, which hunts correctness bugs rather than convention breaches.
---

This is the **convention, architecture and altitude** layer. It is **not** a correctness-bug hunt —
run `/code-review` alongside it, and say so when you finish.

**Input**: nothing (current branch vs `main`), or a PR number.

## 1. Establish the diff

- Current branch: `git diff main...HEAD`, plus `git diff` and `git diff --staged` for uncommitted
  work, and `git log main..HEAD --oneline` for the commit shape.
- A PR number `N`: `gh pr diff N`, plus `gh pr view N` for the description and base branch.

Write 2–4 lines: what changed and which modules and surfaces it touches. Review only the sections
that apply; list the rest as N/A at the end rather than padding.

## 2. Route by what the diff touches

The **Where to look** table at the top of [`CLAUDE.md`](../../../CLAUDE.md) maps each area to its
guide and to the tests that enforce it. Open the guide for every area the diff touches and review
against it — that is the source, and it stays current. Then run those guards (§4) rather than
asserting from reading.

## 3. What a review adds that the build cannot

The guards catch CSP violations, missing authorization markers, clock misuse, migration edits and
markup drift. Spend the review on what they cannot see:

- **Altitude and shape.** Is this the smallest design that works? Flag speculative abstraction,
  indirection with one caller, interfaces with one implementation, knobs nobody asked for. Does new
  code sit in the right module — business logic in `epistola-core`, not leaked into `apps/epistola`;
  feature modules talking through SPIs and CQRS queries rather than injecting each other's services?
- **Stability of GA surfaces.** Since 1.0.0, REST APIs, catalog wire formats, configuration and
  architecture are stable: breaking one needs a **major release and a deprecation path**, not a
  `feat!` in a minor. Features explicitly labelled alpha or beta are the exception — they may break
  in a MINOR, and the CHANGELOG entry must say so. Data stability is absolute and separate: a
  destructive migration, or an edit to an already-released one, is a blocking finding regardless of
  maturity.
- **Compatibility layers, judged both ways.** On a GA surface a compatibility shim may be exactly
  right. Elsewhere, flag version-tolerance layers, "V2 keeps the old behaviour" mechanisms and flags
  that preserve old behaviour for their own sake as waste — but never at the cost of stored data.
- **Three-surface parity.** A capability change usually belongs on all three: the web UI
  (`apps/epistola` handlers and templates), REST (`modules/rest-api`, implementing the external
  `epistola-contract`), and MCP (`modules/epistola-mcp`). Shipping on one and silently drifting the
  others is a finding; an explicit decision to scope it is not.
- **Demo catalog (a PR blocker, and the most-missed one).** Every user-facing capability is
  exercised in `apps/epistola-demo/src/main/resources/epistola/catalogs/demo/`, with realistic
  variants. If it genuinely cannot be, the PR must say why.
- **Bundled-catalog fingerprints.** Any touch under `…/catalogs/{demo,system}/` must bump that
  catalog's `release.version` **and** regenerate `release.fingerprint` — loaders detect change by
  fingerprint, so a stale one silently ships unchanged content. Both catalogs have their own test
  (§4); the demo one lives in `apps/epistola-demo`.
- **Operator impact.** A new config property, env var, feature toggle or operator-visible migration
  needs the operator-facing docs (`docs/`, `charts/epistola/`, README) and the changelog to say what
  an operator must do. A knob with no documented default or effect is a finding.
- **Changelog entry.** Under `[Unreleased]`, as `- [**[user|dev]** ]type(scope)[!]: **Title.** …`.
  Helm changes go in `charts/epistola/CHANGELOG.md`. A test rejects a malformed entry, so check the
  content: does it tell a reader what changed and why?
- **Test tier and honesty.** New behaviour tested at the right level (see the `tests` skill), failure
  paths covered, nothing left `@Disabled`. Claims in the PR description backed by a command that was
  actually run.
- **Docs that describe changed behaviour** updated — including `CLAUDE.md` when a convention moved.

## 4. Verification commands

Run the ones the diff touches and cite results in findings.

```bash
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"   # CSP, UI/REST, clock, markup
./gradlew :modules:epistola-core:unitTest --tests "*BundledCatalogFingerprintTest"   # system catalog
./gradlew :apps:epistola-demo:unitTest --tests "*DemoCatalogFingerprintTest"         # demo catalog
./gradlew ktlintCheck && pnpm format:check
```

App-level tasks need `pnpm build` once first.

## 5. Output

Group by severity:

- **Blocker** — failing guard, destructive or edited migration, GA surface broken without a major,
  missing demo coverage or fingerprint bump, UI calling `/api/**`.
- **Should-fix** — design, maintainability or coverage issues.
- **Nit** — minor.

Each finding: `file:line` · the rule it breaks · a concrete fix. Then an explicit **N/A** line for
the sections that did not apply, and the closing reminder: **"Run `/code-review` for correctness bugs
and simplification — this review covered conventions and altitude only."**
