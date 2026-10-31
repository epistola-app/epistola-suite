# ADR 0044: Quality check results, keyed by content

- **Status:** Proposed — a pilot is being built (see [The pilot](#the-pilot))
- **Date:** 2026-10-31
- **Deciders:** Epistola team
- **Tags:** quality, checks, language, caching, privacy, scheduling
- **Supersedes:** [ADR 0012](0012-check-run-lifecycle.md) (draft),
  [ADR 0031](0031-quality-findings-never-out-of-date.md) (proposed)

## Context

The quality feature ([`docs/quality.md`](../quality.md), alpha) is a ledger. Sources submit the full
set of findings for a subject, and the ledger reconciles it: new fingerprints open, present ones
refresh, missing ones resolve. Ignores, comments and hand-raised notes live beside the findings.
Two records designed the work around it and neither was built: ADR 0012 (July 2026) on scheduling
runs, ADR 0031 (20 October) on stamping each run with the inputs it read.

The next checks are language checks: spelling, grammar, punctuation, writing style and readability,
from a built-in engine and optionally from LanguageTool, Vale or a model hosted by the organisation.
They bring requirements the ledger was not designed for:

- **Providers are slow and can fail.** A call takes a second, not a millisecond, and a server can be
  down. The author must see which checks ran, which failed, and that older results are older.
- **The same text is everywhere.** A sentence appears in many templates, in every variant and in
  every draft; a catalog of 300 templates checked per variant sends the same paragraphs to a provider
  hundreds of times.
- **Text must not leak.** Only template text is checked, never generated documents or data, and text
  goes to a service outside the installation only when someone allowed it.
- **Findings point at words**, not blocks, and carry suggested replacements the author accepts or
  dismisses. Nothing is changed without the author.
- **Writing profiles** (target level, tone, discouraged words, sentence length) configure the checks.
  Changing one must re-check what it applied to.

And 2.0 changed the ground the ledger stands on ([ADR 0038](0038-catalogs-in-2-0.md)): every resource
of every catalog version is stored by content hash, and a catalog has several drafts. The ledger knows
no version: `RunQualityChecks(variantId)` names none, so the panel of a draft other than the next one
checks and shows the next version's variant.

Starting again from these requirements, rather than from the ledger, gives a different shape. That is
what this record decides.

## Considered options

- **A. Keep the ledger; add run stamps** (ADR 0031, and this record's first draft). Findings stay
  rows with a lifecycle, each run records what it read, outdated findings are found by comparing
  stamps. It answers staleness, but every variant is still checked whole, nothing is reused between
  templates, and runs per version have to be bolted onto a versionless ledger.
- **B. Compute findings when they are read, store nothing** (ADR 0031's option C). Never stale, but
  the report filters, counts and pages across a catalog, which would then happen in application code
  (`docs/quality.md`, and the rule against in-memory pagination), and a slow provider would be on the
  read path.
- **C. Check in the browser.** Instant, but a second engine that disagrees with the server's, and no
  report. Checking on save is fast enough for authors.
- **D. Results keyed by content (chosen).** A check is a pure function of what it reads, and 2.0
  stores what it reads by hash. So a result is a cache entry: computed once per distinct input, reused
  wherever that input appears, and never invalidated, only no longer looked up.

## Decision

### 1. Three kinds of state, kept apart

| State             | What it is                                                                                       | Lifetime                        |
| ----------------- | ------------------------------------------------------------------------------------------------ | ------------------------------- |
| **Results**       | What a checker reported for one input, keyed by (checker, its configuration, locale, input hash) | Immutable; reaped when unused   |
| **Decisions**     | What people said: ignores, dismissed suggestions, comments, hand-raised notes                    | Kept; keyed by problem identity |
| **Findings view** | Per catalog version and variant: the results for its current content, with decisions applied     | Derived; can always be rebuilt  |

The findings view is materialized, so the report keeps filtering, counting and paging in SQL. It holds
nothing that cannot be recomputed from results and decisions. "Resolved" stops being a stored status:
a problem is resolved when the current result no longer contains it, and outdated when the view's
content has a key no result exists for yet.

### 2. Each checker checks its natural unit

A checker declares its unit:

- **Text block** (language checks): one paragraph, heading or list item, as extracted text (3.). Its
  key is (checker, configuration, locale, hash of the extracted text). An edit re-checks only the
  blocks it changed; a block shared by many templates is checked once.
- **Variant** (structural checks: alt text, fonts, stencil fit): the variant's content hash plus the
  hash of everything it looked up, a lookup that found nothing included.

The configuration in the key covers the checker's version and the writing profile. A profile change or
a checker upgrade produces new keys; nothing is invalidated.

### 3. Text is extracted once, by the suite

Language checkers never parse templates. The suite extracts checkable text from a template's `text`
nodes into blocks, each holding text and **placeholders**. A placeholder stands for an expression and
is sent as an opaque token, never as a value: example data and generated documents never reach a
checker. The extraction is pure, keyed by the content hash it came from, and is the same step tenant
search needs for content ([ADR 0043](0043-tenant-search.md)'s `searchText`), so the two share it.

A stencil's text is extracted from the stencil and reported against it, not again inside every
template that uses it. A template's model holds stencil instances by reference, so this falls out of
the extraction.

### 4. Checkers are stateless; the suite calls them

```
check(blocks, locale, configuration) → diagnostics per block
```

A checker holds no state and pushes nothing. The suite owns the cache, scheduling, decisions and the
decision about what text leaves the installation. A remote provider is an adapter around one call. The
push ingest for remote sources (`docs/quality.md`) stays for producers that are genuinely independent,
such as the hub, and is not how a language checker connects.

A diagnostic is shaped after LSP diagnostics and code actions, because that shape already serves every
editor: a range in the block, rule id, category (spelling, grammar, punctuation, style, readability),
severity, message, a documentation link, and suggestions as replacement text. Each checker also
publishes its **rules** (id, category, default severity, description), so a profile can configure them
and the author can see which checks are available.

### 5. Where text goes is declared and allowed

A checker declares where it processes text: `in-process`, `installation` (a service the operator runs,
such as a self-hosted LanguageTool) or `external`. The operator configures every checker that is not
in-process; an `external` checker also needs each tenant's explicit consent before any of its text is
sent. The suite's call is the only path text takes to a checker, so this is enforced in one place.

### 6. Checking on save

A save (`UpdateVariantContent`, whichever of web, REST or MCP made it) schedules a check of the variant
as a cluster timer keyed by the variant, a few seconds out. Saving again moves the timer, so a burst of
autosaves is one check. The check extracts the blocks, looks up results, calls each checker for the
keys it is missing only, stores them, and refreshes the variant's findings view. A checker that fails
stores nothing: its previous results stay, shown as outdated where the content moved on, and the
failure is shown to the author. Checking on save is the requirement; checking while typing is not.

### 7. Problem identity and decisions

A finding's identity, which decisions attach to, is (checker, rule, owner, fingerprint), where the
owner is the block's node or the variant and the fingerprint includes the flagged text: fixing one
typo and making another is a different problem, and must not inherit the first one's ignore. That is
the fingerprint contract `docs/quality.md` already states, kept. A suggestion is accepted by the
author in the editor, as an ordinary edit that is then saved.

### 8. Versions

The findings view is per catalog version, because it is derived from that version's content. A draft
shows its own findings; the report shows the next version's; a release keeps the view it was released
with. A draft cut from a release shares every hash with it, so it starts with all its results cached.

## The pilot

Built to the full standard, merged as alpha, and deliberately a slice: it proves the new pieces and
borrows the ledger as the findings view, so the report and the panel work unchanged.

**Built:**

- Text extraction from `text` nodes into blocks with placeholders (3.).
- A result cache table keyed by (tenant, checker, configuration, locale, block hash) (1., 2.).
- The checker interface and diagnostics (4.), with two checkers:
  - **Writing rules**, built in and in-process: repeated words, long sentences, spacing, and a small
    list of discouraged words per language, the start of a default clear-language profile.
  - **LanguageTool**, over HTTP to a server the operator runs (`installation`), off unless configured.
- Checking on save through a debounced cluster timer, beside the existing ready and "Check now"
  triggers (6.).
- Each checker submits to the ledger as its own source, so a failing LanguageTool leaves its findings
  untouched while the writing rules still run (the ledger's per-source reconciliation).
- The editor panel shows the flagged text and suggestions, and after "Check now" which checkers ran,
  failed or were skipped.

**Not built yet:** splitting decisions from the ledger and the findings view per version (the pilot
still has the versionless ledger); moving structural checks onto the cache; rule manifests and writing
profiles; `external` checkers and tenant consent; stencil text as its own subject; underlining the span
in the text editor and applying a suggestion with a click; reaping unused results.

**Measured by the pilot:** how often a save finds its blocks cached, how long a check takes with and
without LanguageTool, and how many findings per template a realistic catalog produces.

## Consequences

- A check never runs twice on the same input in a tenant, and an edit costs a check of what it changed.
- No finding is current when its input changed: the key is the input.
- Text reaches a checker only as extracted template text with placeholders, through one call path
  that knows where each checker sends it.
- Providers are adapters to a stateless call. Adding Vale or a model is a class, not a protocol.
- The ledger's reconciliation, stored resolution and versionless subject are replaced, in steps; the
  pilot keeps them so nothing changes for the report while the cache proves itself.
- Results grow with distinct text. They need reaping (results no findings view references), which the
  pilot leaves open.

## Open questions

- Whether results are shared between tenants. Sharing would save provider calls for common text, but
  would let a tenant learn whether another tenant has a sentence; the pilot keeps them per tenant.
- Which writing-profile settings belong to the checker configuration (in the key) and which are
  applied afterwards to cached results (severity, which rules are shown), which would keep a profile
  edit from re-checking anything.
- How the findings view is kept up to date when content changes without a save that schedules a check
  (an import, a relocation).

## References

- [`docs/quality.md`](../quality.md): the ledger as built, and the fingerprint contract.
- [ADR 0011](0011-quality-check-input-model.md): declared data requirements, kept for structural
  checks.
- [ADR 0012](0012-check-run-lifecycle.md) and [ADR 0031](0031-quality-findings-never-out-of-date.md):
  superseded by this record.
- [ADR 0038](0038-catalogs-in-2-0.md): content hashes and catalog versions.
- [ADR 0043](0043-tenant-search.md): the text extraction search needs.
- [`docs/timers.md`](../timers.md): cluster timers, the save trigger.
