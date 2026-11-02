# ADR 0044: Quality check results, keyed by content

- **Status:** Proposed — a pilot is built (see [The pilot](#the-pilot))
- **Date:** 2026-10-31
- **Deciders:** Epistola team
- **Tags:** quality, checks, caching, scheduling, privacy
- **Supersedes:** [ADR 0012](0012-check-run-lifecycle.md) (draft),
  [ADR 0031](0031-quality-findings-never-out-of-date.md) (proposed)
- **Related:** [ADR 0045](0045-locations-in-a-template.md) (locations),
  [ADR 0046](0046-quality-profiles-and-rules.md) (profiles and rules),
  [ADR 0047](0047-quality-providers.md) (providers)

## Context

The quality feature ([`docs/quality.md`](../quality.md), alpha) is a ledger. Sources submit the full
set of findings for a subject, and the ledger reconciles it: new fingerprints open, present ones
refresh, missing ones resolve. Ignores, comments and hand-raised notes live beside the findings.
ADR 0012 (July 2026) designed scheduling runs and ADR 0031 (20 October) stamping each run with the
inputs it read. Neither was built.

The next checks read a template's **text** (spelling, grammar, style, readability) through providers
such as a built-in engine, LanguageTool or an LLM, against rules an organisation records in quality
profiles. They need what the ledger was not designed for:

- **Slow, failing providers.** A call takes seconds, a server can be down, and the author must be told.
- **The same text everywhere.** A paragraph appears in many templates, variants and drafts.
- **No leak.** Only template text and structure, never generated documents or data.
- **Findings within a block**, with suggested replacements, at a location shared with the preview
  (ADR 0045).

And 2.0 changed the ground ([ADR 0038](0038-catalogs-in-2-0.md)): every resource of every catalog
version is stored by content hash, and a catalog has several drafts. The ledger knows no version, so
the panel of a draft other than the next one checks and shows the next version's variant.

This record decides how results are computed, stored, kept current and scheduled. What is checked
(rules) is ADR 0046; who checks (providers) is ADR 0047; where a finding points is ADR 0045.

## Considered options

- **A. Keep the ledger; add run stamps** (ADR 0031). Answers staleness, but checks every variant
  whole, reuses nothing between templates, and bolts versions onto a versionless ledger.
- **B. Compute findings when read, store nothing.** Never stale, but the report's filtering, counting
  and paging would move into application code, and a slow provider would sit on the read path.
- **C. Check in the browser.** Instant, but a second engine that disagrees with the server's, and no
  report. Checking on save is fast enough.
- **D. Results keyed by content (chosen).** A check is a function of what it reads, and 2.0 stores
  what it reads by hash. A result is a cache entry: computed once per distinct input, reused wherever
  that input appears, never invalidated, only no longer looked up.

## Decision

### 1. Three kinds of state, kept apart

| State             | What it is                                                                                              | Lifetime                        |
| ----------------- | ------------------------------------------------------------------------------------------------------- | ------------------------------- |
| **Results**       | What a provider reported for one unit, keyed by (provider, its configuration, rules, locale, unit hash) | Immutable; reaped when unused   |
| **Decisions**     | What people said: ignores (with a reason), comments, hand-raised notes                                  | Kept; keyed by problem identity |
| **Findings view** | Per catalog version and subject: the results for its current content, with decisions applied            | Derived; rebuildable            |

The findings view is materialized, so the report keeps filtering, counting and paging in SQL, and
holds nothing that cannot be recomputed. A problem is **resolved** when the current result no longer
contains it and **outdated** when the subject's content has a key no result exists for yet.

### 2. The unit is the provider's

Each provider declares its unit (ADR 0047):

- **A text block** (crude rules, LanguageTool): one paragraph or heading, extracted as text with
  placeholders (3.). Its key holds the hash of that text. An edit re-checks the blocks it changed; a
  block shared by many templates of a tenant is checked once.
- **A subject** (an LLM, structural checks): a variant or stencil, keyed by its content hash.

The key always holds the provider's configuration (its version and settings) and the hash of the
rules it was given (ADR 0046), so a profile edit re-checks only with the providers whose rules
changed, and an upgrade is a new key, not an invalidation.

**A result that read more.** A provider that asks for more of the template while checking (an LLM
through its tools, ADR 0047; a structural check resolving a stencil) records each read by what it
named and the hash of what it got, a read that found nothing included. Such a result is current
while every read would still return the same. This is ADR 0031's stamp, kept only where a result
depends on more than its unit.

### 3. Text is extracted by the suite, schema-aware

Providers never parse templates. The suite extracts every field whose component-registry role is
**prose** (ADR 0045) into units of text with **placeholders**: an expression becomes one opaque
character, so a provider never sees an expression's value, and sees its source only through a tool
its scope allows. Example data and generated documents never reach a provider. The extraction is
pure, keyed by the content hash, and shared with tenant search's content extraction
([ADR 0043](0043-tenant-search.md)).

### 4. Subjects: variants and stencils

A stencil is checked as itself and its findings are recorded on it. A template that uses the stencil
shows them too, marked as the stencil's, because a stencil is edited through the templates that use
it; fixing or ignoring one there acts on the stencil's finding. One typo in a stencil is one finding.

### 5. Runs and triggers

A **trigger** (a save, "Check now", a ready mark, a release, a schedule) schedules the subject's
check on a cluster timer keyed by the subject, written in the trigger's own transaction: a burst of
saves moves the timer and is checked once; a save that rolls back leaves no timer. When the timer
fires, the runner asks each provider whose configuration includes that trigger (ADR 0047) for the
units it has no result for, stores the answers, and refreshes the findings view. "Check now" is the
one trigger that runs in the request, because the author waits for it.

A provider that fails stores nothing: its previous results stay, shown as outdated where the content
moved on, and the failure is shown. A provider that does not apply (not enabled for the tenant, no
language chosen) is shown as skipped. Neither ever resolves a finding.

### 6. Versions

The findings view is per catalog version: a draft shows its own findings, the report the next
version's, and a release keeps the view it was released with, which blocking rules read (ADR 0046).
A draft cut from a release shares every hash with it, so it starts with its results cached.

## The pilot

Built and merged as alpha, a slice that borrows the current ledger as the findings view:

- text extraction from `text` nodes, with placeholders; the result cache
  (`quality_text_results`) per tenant;
- the built-in engine running typed rules with parameters from a built-in default set (until
  profiles exist, ADR 0046), and LanguageTool over HTTP as an installation provider, switched on per
  tenant (ADR 0047);
- per-provider triggers; saves and ready marks on a cluster timer; "Check now" in the request;
- a failing provider keeps its findings, and the panel says what was skipped or failed.

**Not built yet:** decisions apart from results and the view per version (the next step, before
profiles and LLMs); locations (findings point at node ids, the quote in their evidence); schema-aware
extraction; stencils as subjects; recorded reads; reaping unused results.

**Measured by the pilot:** how often a save finds its blocks cached, how long a check takes with and
without LanguageTool, and how many findings a realistic catalog produces.

## Consequences

- A unit is checked once per key in a tenant; an edit costs a check of what it changed.
- No finding is current when its input changed: the input is the key, or its recorded reads are.
- Text reaches a provider only as extracted template text with placeholders.
- The ledger's reconciliation, stored resolution and versionless subject are replaced in steps; the
  pilot keeps them so the report does not change while the cache proves itself.
- Results grow with distinct text and need reaping (results no findings view references).

## Open questions

- Sharing results between tenants: saves calls, but would tell one tenant whether another holds a
  sentence. Per tenant for now.
- How the findings view follows content changed without a trigger (an import, a relocation): a
  storage event, or a periodic sweep as backstop.

## References

- [`docs/quality.md`](../quality.md): the ledger as built, the fingerprint contract.
- [ADR 0011](0011-quality-check-input-model.md): declared data requirements, kept for structural checks.
- [ADR 0038](0038-catalogs-in-2-0.md): content hashes and catalog versions.
- [`docs/timers.md`](../timers.md): cluster timers.
