# ADR 0011: How a quality check receives what it inspects

- **Status:** Draft — discussion record, not accepted
- **Date:** 2026-07-17
- **Discussants:** Epistola team
- **Tags:** quality, architecture, hub, checks, model, serialization, efficiency, async

## Context

The quality feature is a **ledger**: sources submit findings, the ledger owns them (see
[`docs/quality.md`](../quality.md)). This ADR is not about the ledger. It is about the **other
half** — how a check gets the _thing it inspects_ — and it must hold up under three pressures the
team named explicitly: it should be **efficient**, checks must be able to run **remotely** (on
epistola-hub), and some checks need **asynchronously-produced** input (the rendered PDF).

Today an in-process check is a **pure function** from a `QualityCheckInput` to a list of findings:

```kotlin
data class QualityCheckInput(
    val subject: QualitySubject,
    val templateModel: TemplateDocument,
    val dataExamples: List<DataExample>,
    val dataModel: ObjectNode?,
)
fun check(input: QualityCheckInput): List<SubmittedFinding>
```

Purity is deliberate: a check unit-tests with a plain fixture and no Spring context, and cannot
reach into the database, so it cannot see user data or run an unbounded query mid-sweep. The first
real checks (the example rules, the accessibility/alt-text rule) needed nothing but
`templateModel` — pure over what they were handed.

**The pressure comes from the next checks.** A useful check often needs a _resolved_ fact that
lives in the database, or an artifact produced elsewhere:

| Check                                     | Needs                                  | Where it comes from       |
| ----------------------------------------- | -------------------------------------- | ------------------------- |
| Font names a family that isn't installed  | does font _X_ resolve to a face?       | `font_variants` (DB)      |
| Image is far larger than its display box  | the asset's pixel dimensions           | `assets` row (DB)         |
| A bound data path no example fills        | referenced paths vs. example data      | pure (extractor + data)   |
| Template pins an outdated stencil version | the stencil's latest published version | `stencil_versions` (DB)   |
| PDF/A conformance, real accessibility     | the rendered PDF bytes                 | render pipeline (Phase 5) |

A pure check cannot fetch any of it, so **someone must produce it and get it to the check**. The
in-flight code took the obvious first step — widen the input with a pre-resolved font map — which
works but raised the real question this ADR settles: is the input meant to accrete a bespoke map per
check, become one canonical model, carry resolver callbacks, be fed from a precomputed store, or
something else?

## The key insight: this is two separate questions

Earlier drafts of this ADR listed the candidate designs as one flat menu. That obscured the
structure. There are **two independent layers**, and conflating them is what made the space feel
both crowded and incomplete:

1. **The contract** — how a check _expresses_ what it needs. This is check-facing and should be
   uniform across every check, local or remote.
2. **The fulfillment** — how the framework _satisfies_ a need. This is where efficiency, remote,
   and async are evaluated **per requirement**, not once for the feature.

A check declaring "I need the fonts resolved" says nothing about whether that is resolved on read,
served from a precomputed table, or shipped from the hub. Keeping the two layers separate is what
lets one contract serve cheap-synchronous facts and the asynchronous PDF alike.

## Evaluation drivers

- **Check purity / testability.** A check being a pure function of the data it is handed is the
  SPI's core virtue — a plain fixture, no Spring, no DB, deterministic. Trading it away is a real
  cost.
- **Efficiency.** The sweep runs every check over every variant; a design that recomputes cheap
  facts every sweep, or ships bytes no check reads, is wrong. Metadata is cheap; **bytes** (asset
  content, PDF) must never be materialized speculatively.
- **Remote execution.** Checks run on the hub. Whatever the contract is, it must extend to a
  remote check without contorting the in-process shape.
- **Async input.** The rendered PDF is produced by the generation pipeline (a queue, a completion
  event, possibly another node). It cannot be pre-fetched synchronously.
- **Compatibility surface.** The moment something is _serialized_ across a boundary, its shape is a
  contract with migration weight (cf. ADR 0007, the catalog wire format).
- **Reversibility / YAGNI.** Adding a field to an in-memory Kotlin input is cheap and private.
  Defining a canonical serialized schema, or a materialized-fact store, is a standing commitment —
  worth it only with a consumer that justifies it. This feature has repeatedly punished modelling
  ahead of need.
- **Longevity at scale.** The design must still be maintainable, performant, and pleasant to use at
  _hundreds_ of checks, millions of findings, and the hub in the loop — not just at today's three
  checks. See [Longevity, scale, and UX](#longevity-scale-and-ux-the-years-out-view); it is a
  first-class driver, not an afterthought.

---

## Layer 1 — the contract (how a check expresses its needs)

### 1A — Bespoke fields per check

Widen `QualityCheckInput` with a field per check's need (a font-resolution map, then a stencil map,
then an asset map). **Pros:** trivial, incremental. **Cons:** the input is a grab-bag; the generic
runner must know what each check wants and resolves it unconditionally (it resolves fonts whether or
not the font check runs); no uniform story for remote. This is the in-flight state, and the smell
that prompted this ADR.

### 1B — One canonical "compiled template" model

A single serializable `CompiledTemplate` (model + examples + theme + fonts + assets + stencils +
later a render reference) that every check consumes. **Pros:** one subject of checking;
persistable/diffable; matches the "check the whole compiled thing" vision. **Cons:** a large
up-front schema and a compatibility surface built _before_ any consumer needs it; eager-building it
per variant per sweep is wasteful; bytes/PDF never really fit "one JSON". Strong as a possible
destination, premature as the _starting contract_.

### 1C — Resolver ports (the check pulls)

Hand the check the model plus resolver interfaces it calls on demand. **Pros:** lazy. **Cons:**
**spends purity** — a check is no longer a pure function of data, and every test needs stub ports;
resolvers do not serialize, so they are useless to a remote check; invites N+1. Rejected.

### 1D — Declared data requirements (the check declares; the framework provides)

A check declares a **requirement manifest** — an enumerated `Set<DataRequirement>` of coarse input
families (`RESOLVED_TEMPLATE_DEPENDENCIES`, later `RENDERED_PDF`,
`RAW_CATALOG_MATERIALS`, …). The framework computes the **union** of what the enabled checks
declared, produces precisely that (once per subject, shared), and hands each check exactly the data
it asked for. The check still _receives resolved data_ and stays pure — the declaration is static
metadata beside `check()`, not a resolver it calls. It is 1C turned inside out (declare instead of
pull), keeping 1A's purity while removing its unconditional-resolution waste. The shape is familiar:
GraphQL field selection, or a build system's declared inputs.

Three properties make 1D the right contract:

- **The declaration, not the transport, is the contract.** A local check and a remote check declare
  identically; only fulfillment differs (Layer 2). One SPI serves both.
- **"Example data only, never user data" becomes structural.** User data is not a declarable
  requirement, so a check _cannot_ ask for it. The guarantee moves from a comment to the type
  system — worth a lot for a plugin-style, partly-remote check ecosystem.
- **The runner stops being a per-check switchboard.** It resolves the union of _declared_ needs, so
  nothing is produced for a disabled check.

---

## Layer 2 — fulfillment (how the framework satisfies a requirement)

The contract (1D) deliberately says nothing about _how_ a requirement is produced. That is chosen
**per requirement**, from a small menu, by the requirement's cost, freshness needs, and locality.
This layer is where the team's efficiency/remote/async pressures are actually answered.

### 2-read — Resolve on read

Query when the check runs. Simple; correct for the nightly sweep (produce once a night). The
**least efficient** default — it recomputes every sweep and, for remote, means resolving then
shipping. Use for cheap facts at sweep frequency.

_Worked example — `STENCIL_CURRENCY` (synchronous, resolve-on-read):_ scan the model for
`type == "stencil"` nodes → `(catalogKey ?: template's catalog, stencilId, pinnedVersion)`;
resolve each distinct coordinate's latest published version via the existing
`ListStencilVersions(id, status=PUBLISHED).first().id` (the version id **is** the version number,
ordered DESC); facet is `Map<StencilCoordinate, Int?>`; the pure check flags `latest > pinned`. A
few indexed reads per distinct stencil, in-thread. (Resolver and check both scan the model, so they
share one `stencilCoordinateOf(node)` helper to agree on the `null catalogKey → template's catalog`
normalization.)

### 2-materialized (F) — Precompute on change, store, read on demand

Compute a fact _when its inputs change_ (template save, stencil publish, font upload) and store it;
checks read it. Precedent already exists: `template_versions.referenced_paths` is computed on save
and stored. **Efficiency:** compute once per change instead of once per sweep — a real win when
checks run more often than templates change (the editor "check now" path) or when the fact must
travel to the hub (it rides the catalog snapshot for free). **Cost:** invalidation machinery, and
the fact tables become a mild schema surface. Use for **hot** or **remote-shipped** facts.

### 2-async — Cache-with-async-miss (for produced artifacts)

For a requirement that cannot be pre-fetched — the rendered PDF — the resolver first checks storage
for a fresh artifact of `(version, example)` at the current engine version. **Hit** → hand over the
bytes, the check runs now (the common case; documents are usually already rendered). **Miss** (first
render, or a post-upgrade engine change) → submit a `GenerateDocument` carrying the quality marker;
the pipeline renders; on the terminal transition `EmitGenerationResult` fires a completion event
_instead of_ emitting to the collect feed; a handler in the quality module then runs the checks that
declared `RENDERED_PDF` with the bytes. This is the Phase 5 machinery, expressed as one requirement's
fulfillment. Its knock-on effects are real and belong on the requirement:

- **When** — the check runs in a completion event, not the sweep (which _cannot_ render: a long
  render stalls the cluster liveness stamp — a hard constraint, see Phase 5).
- **Subject** — `RENDERED_PDF` is per `(version, example)`, so it fans out to the finer `RENDER`
  subject; a check's subject is implied by its coarsest async requirement.
- **Trigger** — produced on publish/upgrade, not nightly.
- **Failure honesty** — a render that fails or never completes means the check does not run, and its
  prior findings must go **stale, not falsely resolved** (a source's "full set" cannot be submitted
  if the source never ran). The one genuinely new failure mode.

### 2-push/fetch — Run-scoped input grants (the remote transport)

**Candidate remote model.** A remote check declares its requirements; the suite produces an
immutable input manifest for one run and either **pushes small data inline** or grants
**short-lived, run-bound artifact handles** for large/expensive data such as rendered PDFs. The
remote check is a pure function of the granted run inputs, symmetric with in-process. It is not a
live tenant API client and cannot browse state outside that run. A remote check gets what it asked
for — not the whole compiled model (avoiding 1B's overclaim), not nothing (avoiding "go resolve your
own"). Three consequences:

- The existing **catalog snapshot** the hub receives (via `epistola-support-snapshots`) _is_ the
  pushed fulfillment of a `RAW_CATALOG_MATERIALS` requirement. So compatibility's "render on the hub
  on a newer engine" is not an exception to push — its declared requirement is the raw materials,
  pushed the way they already are; the engine-version-specific _rendering_ is the hub's own step on
  top of pushed input.
- A wire payload shape is defined **only for requirements a remote check actually declares**,
  incrementally — never a big-bang schema.
- **Input grants have two sub-shapes.** Small facts ride in the manifest as JSON; **bytes** (a
  rendered PDF) ride as a streaming/content-addressed artifact handle — the shape
  `epistola-support-snapshots` already uses for catalog snapshots. Content-addressing means the hub
  receives each unique artifact once.
- Remote results must echo the `run_id` and `input_fingerprint`; a late result for an old draft is
  rejected or recorded obsolete, never stamped as current.

#### Worked end-to-end: a remote PDF-conformance check (the capstone case)

A hub check that validates PDF/A conformance needs **both** the rendered bytes _and_ the source
that produced them (veraPDF reports "object 47 violates rule X"; only the source maps that back to
"the logo on page 2, node `n-header`"). It composes existing pieces with nothing new:

1. **Declares** `{ RENDERED_PDF, COMPILED_SOURCE }` on the `RENDER` subject `(version, example)`,
   `source_id = "hub.pdf-conformance"`. Triggered on publish/upgrade, never autosave
   (trigger-tiering, ADR 0012).
2. **`RENDERED_PDF` — the _suite_ renders** (conformance validates _this installation's_ output),
   via 2-async cache-with-async-miss. (Contrast the compatibility check, which declares
   `RAW_CATALOG_MATERIALS` and renders _itself_ on a different engine — same machinery, the
   _declaration_ encodes which it wants.)
3. **`COMPILED_SOURCE` — resolved** (model + example + resolved deps), serializable.
4. **Grant input** `{ pdfBytes (streaming/content-addressed handle), compiledSource (JSON) }` to
   the hub.
5. Hub validates (bytes) and attributes (source), pushes its **full finding set** back through the
   ingest → reconciled under `hub.pdf-conformance`, subject = that `RENDER`, `source_id`-scoped like
   any source. Findings land in the ledger; the editor/report read them locally; the hub's latency
   never touches the author (the ledger's async submission is the pressure-relief).

Bounded end to end: render-cache (skip unchanged) → content-addressed byte-dedup (validate each
unique PDF once) → tiering (publish only) → async submit (off the critical path).

### Strategies considered and not adopted now

- **Content-addressed compiled artifact with build caching (G).** 1B built incrementally and cached
  by input hash (Bazel-style), shared across checks and shipped content-addressed. Efficient, but the
  heaviest to build; recorded as the optimization to reach for _if_ a materialized compiled artifact
  is later needed.
- **Reactive / event-sourced checks (H).** Checks as stream processors that maintain findings from a
  change/render event stream, with no gather step. Async falls out for free and it is naturally
  remote, but it inverts the pull model, makes "run all checks now" awkward, and turns findings into
  a stream projection. Too large a paradigm shift for the currently-known value; noted as a tradeoff.
- **Ship the check to the data / computation locality (I).** Run a remote check where its data
  already lives rather than pushing data to it. Considered as a primary remote model, but currently
  less attractive than run-scoped grants because the latter preserves the symmetry that every check,
  local or remote, is a pure function of provided data. Locality may still matter in narrow cases,
  such as the hub rendering where its engine versions live.

## Candidate direction

**Contract candidate: Option 1D — declared data requirements.** Every check declares a coarse
`Set<DataRequirement>`; the framework provides the union; checks stay pure functions of provided
data. Requirements are capability families, not one enum entry per eventual fact. The first
implemented family is `RESOLVED_TEMPLATE_DEPENDENCIES`: references discovered in the checked
template, resolved against current tenant/catalog state. It starts with fonts because unresolved
fonts are the first DB-backed source, and can grow asset/theme/stencil/code-list facts when real
sources need them.

**Fulfillment candidate: a per-requirement menu (Layer 2), chosen by cost/freshness/locality.** Cheap
sweep-frequency facts resolve on read (2-read); hot or remote-shipped facts may be materialized
(2-materialized / F); the rendered PDF uses cache-with-async-miss (2-async).

**Remote candidate: run-scoped input grants (2-push/fetch).** The suite produces what a remote check declares
as an immutable manifest for one run. Small data is pushed inline; large data is exposed through
short-lived artifact handles. The remote check is a pure function of that granted input. The catalog
snapshot is the push-fulfillment of `RAW_CATALOG_MATERIALS`.

**Canonical `CompiledTemplate` (1B) remains a possible destination, not the starting point.** If a
consumer later needs the compiled view persisted or diffed (the PDF tier, cross-install comparison),
it is built then, as the _materialization of the requirement union_, in its own ADR — not
speculatively now.

## Longevity, scale, and UX (the years-out view)

The failure modes for maintainability, performance, and UX all appear _years out_ — at hundreds of
checks, millions of findings, a big installation, with the hub in the loop — not at the three checks
of today. This section records the multi-year reasoning so any later decision is judged against
where it goes, not where it starts.

### The one maintainability guarantee

**1D is the _contract_; the canonical model (1B) and a cached compiled artifact (G) are _fulfillment
destinations reachable from it without touching a single check._** A check declares a requirement
and reads a fact value; whether that value came from resolve-on-read, a materialized table, a cached
compiled artifact, or a payload pushed from the hub is invisible to it. So every expensive future
move — resolve-on-read → materialized → cached-artifact → pushed — is a **fulfillment swap behind a
requirement, never a contract change**. This is the property that keeps the feature maintainable as
it grows, and the reason 1D currently looks stronger than 1A (every scaling change edits a central runner) and 1B
(a schema committed before its consumers, hard to walk back). **The only thing expensive to change
later is the contract, and 1D is the contract we can hold still while everything behind it evolves.**

### Invariants to hold (cheap now, expensive to retrofit)

1. **Resolvers are collected beans (a `RequirementResolver` registry), not a `when` switch in the
   runner.** Same pattern as the source SPI / `NavContributor`. Adding a requirement adds a bean;
   the runner never grows a per-check branch and never becomes the god-object every new check must
   edit.
2. **Resolved facts are serializable value types** (bytes excepted — lazy/async). One representation
   serves in-process _and_ remote run manifests, so local and wire cannot drift (the ADR 0005
   "modelled three times" trap).
3. **No UX surface ever waits on a check.** Findings are always _read from the ledger_ (fast, local,
   indexed); checks _fill_ the ledger asynchronously (any speed, anywhere). A 30-second hub check or
   a minute-long render never becomes latency a user feels. This is already true and must stay an
   invariant — it is the keystone that makes "highly performant services" and "great UX" compatible
   with slow, remote, ML-heavy checks.
4. **Checking is event-driven-primary, full-sweep-as-backstop.** At scale, re-checking every
   unchanged template every night is _the_ bottleneck. Cost must be O(_changes_) — check on save /
   publish / engine-upgrade / stencil-publish — with the full sweep only a periodic reconciliation
   (missed events, newly-added rules). The machinery can be built incrementally, but the model is
   event-driven from the start; it shapes triggers and subjects. Event-driven checking needs a
   coordination layer to prevent overlapping/redundant runs and to expose run-state to the editor;
   that is the run ledger, explored in [ADR 0012](0012-check-run-lifecycle.md).

### Performance notes that follow

- **Fulfillment is the throttle.** Cheap facts resolve-on-read on a small change-unit; **hot facts
  materialize (F)** so the editor and the hub read without re-resolving; the render tier is a
  **content-addressed cache** so an engine upgrade re-renders only changed inputs; a **per-tenant
  resolution cache** dedups shared facts (one font across 100 templates). None of these changes a
  check.
- **Manifests are for small per-subject facts; corpus-scale requirements ride the resident
  snapshot.** A cross-template / cross-tenant check's requirement is a _corpus_; pushing it per check
  would be the bottleneck. But the corpus is already resident on the hub via the catalog snapshot
  (the push-fulfillment of `RAW_CATALOG_MATERIALS`, delivered once). A run manifest never means
  "ship gigabytes per check."
- **Findings volume** (subjects × sources × history) will eventually need partitioning/retention like
  `generation_results`. Deferred, but the table should be expected to grow into that.

### UX notes that follow

- **Two tiers:** instant client-side hints for the genuinely-fast subset (the `binding-errors`
  pattern) so typing feels live, plus the authoritative async ledger streamed into the panel as
  checks complete. The editor never blocks on a check.
- **Explain / suggest-a-fix** (the likely year-3 AI layer) rides the already-self-describing finding
  (`message`, `docsUrl`, `context`) — no new plumbing.
- **Noise control** (per-tenant rule enable/disable, severity tuning, "hide INFO in the editor")
  builds on the existing toggle + ignore mechanisms when source count makes false-positive fatigue
  the UX risk.

### What is safely deferred — and provably reachable

Materialized-fact store (F), cached compiled artifact (G), the remote run/input/result protocol,
render cache + backpressure, findings partitioning/retention, client instant-hints, per-tenant rule
config. **Every one is a fulfillment or capacity change behind the 1D contract — none reopens the
check SPI.** That reachability is the point: we commit to the contract now and let scale pull the
rest forward.

### Scope of the first implementation (open for sign-off)

Two things are deliberately left for the team to confirm before build:

1. **Build 1D's machinery now, or grow bespoke fields (1A) and refactor to 1D at the second
   DB-backed check?** 1D's minimal form is barely more than 1A — a `requirements: Set<DataRequirement>`
   on the SPI and a `when` dispatch in `RunQualityChecks` that resolves the union — and it sets the
   pattern the feature is explicitly built for, so the current working preference is **1D now,
   resolve-on-read only**. The async tier (2-async) and the remote transport are **not** built now; they arrive with
   Phase 5 and the deferred remote-check ingest respectively.
2. **The starting requirement vocabulary:** keep it deliberately small. Ship only
   `RESOLVED_TEMPLATE_DEPENDENCIES` in code now. It is initially populated with discovered font refs
   because unresolved fonts are the first DB-backed source; add asset/theme/stencil/code-list fields
   to the same facet when sources need them. Name `RENDERED_PDF` and `RAW_CATALOG_MATERIALS` in this
   ADR as future requirement families, but do not make them implementation API before a consumer
   exists.

## Expected consequences if accepted

- The source SPI gains `requirements: Set<DataRequirement>` (default empty — a pure model-only check
  declares nothing, like the example/accessibility checks today).
- `RunQualityChecks` computes the union of the enabled checks' requirements and produces each via a
  **requirement → fulfillment** dispatch, populating facets on `QualityCheckInput` (still the
  in-process carrier: resolved metadata, lazy byte accessors, pure for checks).
- The in-flight font-resolution map is reframed as the first field inside the
  `RESOLVED_TEMPLATE_DEPENDENCIES` facet, resolved on read. The next dependency-backed check (for
  example stale stencils) adds data to that facet and a resolver path, not bespoke source-specific
  input.
- Fulfillment strategy is a **per-requirement** property, documented on each requirement: which of
  resolve-on-read / materialized / async / push it uses. Starting everything at resolve-on-read is
  fine; a requirement is promoted (e.g. to materialized) when its cost or locality demands.
- [`docs/quality.md`](../quality.md) gains a short "how a check gets its data" section pointing here,
  so the "why not one big model" question has a standing answer.
- **Explicitly out of scope now**, recorded as follow-ups: the async tier (Phase 5, `RENDERED_PDF`),
  the remote run/input/result protocol (each remote-declared requirement gets a versioned payload
  _then_, one at a time), and the materialized-fact store (introduced per-requirement when a fact
  goes hot or must travel).

**If instead the team keeps 1A (bespoke fields) as the smaller step:** `QualityCheckInput` grows
eager facets + lazy byte lambdas with no declaration; the runner resolves each unconditionally. The
escalation trigger to 1D is recorded: the moment an expensive facet would be resolved for a disabled
check, or a remote check needs a principled way to ask.

## Related

- [`docs/quality.md`](../quality.md) — the ledger, the sources SPI, and the compatibility-is-separate
  decision.
- ADR 0005 (feedback local vs. remote), ADR 0006 (shipping logs/metrics to hub) — prior suite/hub
  boundary decisions.
- ADR 0007 (catalog wire-format migrations) — the precedent for what a _serialized_ contract costs
  once it crosses a boundary; governs the eventual `2-push` wire payloads.
- ADR 0009 (`event_log` vs `generation_results`) — a prior "two overlapping mechanisms, which earns
  its keep" decision, and the eventing the `2-async` completion path rides.
- `epistola-support-snapshots` — the catalog snapshot the hub already receives; the push-fulfillment
  of `RAW_CATALOG_MATERIALS`.
- `template_versions.referenced_paths` — the existing precedent for a materialized (2-materialized)
  derived fact.
