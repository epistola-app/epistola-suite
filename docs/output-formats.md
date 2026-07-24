<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Multi-format output and channels — a discussion

> **Status: exploratory. Nothing here is decided, and nothing here is scheduled.**
>
> This document exists to think against. It surveys how Epistola might add a second
> output format (HTML) for the same letter, and — later and much more speculatively —
> "channels" such as email. It deliberately does **not** pick winners. Where the
> evidence in the codebase points somewhere, it says so; where the choice is a
> product judgement, it lays out both sides and stops.
>
> When a direction is actually chosen, that decision belongs in an ADR
> (`docs/adr/`), and this document should be updated to point at it.

## Why write this now

Two reasons.

The first is that the question is coming. Today Epistola renders PDF and only PDF.
The intent is eventually to render the same letter as HTML — same content, different
representation — and possibly, further out, to emit an artifact for a _channel_
(an email rather than a page or a document).

The second is that **our existing documentation already claims we did this.**
[`docs/generation.md`](generation.md) contains an architecture diagram with an
`HtmlRenderer` branch, an "Output Formats" table listing four renderer/backend
combinations, and per-renderer performance figures.
[`docs/roadmap.md`](roadmap.md) lists "HTML output, PDF via Puppeteer (Chrome-based)"
as **shipped Phase 1 MVP** functionality.

None of it exists. There is no `HtmlRenderer`, no Puppeteer, no Playwright, no
iText pdfHTML. There is one renderer — `DirectPdfRenderer`, direct to iText —
and the roadmap's "PDF via Puppeteer" actively contradicts it. Anyone planning from
those documents would badly misjudge the work. See
[Findings that are wrong today](#findings-that-are-wrong-today).

## Where we actually are

The honest map, verified against the tree rather than the docs:

**The content model is genuinely medium-neutral.** This is the good news, and it is
not faint praise:

- `TemplateDocument` is a normalized node/slot graph (`{root, nodes, slots}`) with
  **no geometry** — no positions, no page list. It is defined by JSON Schema in the
  external `epistola-model` package and shared by Kotlin and TypeScript.
- Rich text is ProseMirror JSON — already HTML-shaped.
- Styles are CSS-derived (`fontFamily`, `fontSize`, `color`, `padding`, `margin`,
  `border`…), and the theme cascade (theme docStyles → template docStyles → preset →
  inline) maps cleanly onto CSS specificity.
- Data contracts, variants, expression evaluation (JSONata/GraalJS) and
  `RenderCulture` are all medium-independent.

**There is no seam.** No `OutputFormat` enum, no `Renderer` interface. Nothing to
implement against.

**The renderer is the entire cost.** `modules/generation/.../pdf/` is ~25 iText node
renderers plus `TwoPassAnalyzer`, band auto-grow, the page/footer/watermark/address
event handlers, `FontCache`, `StyleApplicator` and `RenderingDefaults`. That is the
work. The API, MCP, storage and catalog surfaces are shallow by comparison.

### The coupling is shallower than the file layout suggests

This is the most useful concrete thing to come out of the survey, because it changes
the cost estimate.

`NodeRenderer.render(...)` returns `List<IElement>` — an iText type
(`modules/generation/src/main/kotlin/app/epistola/generation/pdf/NodeRenderer.kt:13`).
That looks like a deep coupling across ~25 implementations. But:

- `NodeRendererRegistry`'s traversal (`renderNode` / `renderSlot` / `renderSlots`) is
  ~40 lines and **fully generic** — pure graph walking.
- `ConditionalNodeRenderer` (45 lines) and `LoopNodeRenderer` (65 lines) contain
  **zero iText logic**. Read them: extract expression, evaluate, delegate to
  `registry.renderSlots(...)`. The only PDF-specific thing in either file is the
  return type. They are already generic in the element type; they just can't say so.
- `RenderContext` has ~20 fields and exactly **two** are iText-bound (`fontCache`,
  `proseMirrorConverter`), plus three that are paged rather than iText-specific
  (`resolvedPageSettings`, `totalPages`, `bodyContentTopPt`). Everything else —
  data, loopContext, evaluator, styles, parameter scopes, `renderingDefaults` — is
  medium-neutral.

**And the package boundary is inverted from reality, in both directions:**

| File                      | Package says                             | Reality                                                    |
| ------------------------- | ---------------------------------------- | ---------------------------------------------------------- |
| `TwoPassAnalyzer.kt`      | `app.epistola.generation.pdf`            | **Zero** iText imports. Pure template-graph analysis.      |
| `ProseMirrorConverter.kt` | `app.epistola.generation` (neutral root) | Imports 10 iText types, and reaches back _up_ into `.pdf`. |

So `pdf` is not the PDF boundary and the root is not neutral. A real portion of
"separate medium-neutral from PDF-specific" turns out to be moving code to where it
already belongs.

## Fork 0 — one template, or two?

**This question subsumes everything below it.** If the answer is "two templates",
most of this document evaporates. It gets no thumb on the scale here.

### The case for two templates (sharing only the data contract)

Good print design and good screen design genuinely diverge. A letter is A4, ~2cm
margins, ~11pt serif, addressed to an envelope window, footed with "Page 2 of 3",
designed to be folded. An email is one column, ~600px, sans-serif, unpaginated, with
no envelope. These are not one artifact with different CSS.

The evidence is in our own component set:

- `addressblock` exists **solely** for DIN C5/C6 envelope-window geometry — it
  positions content at absolute page coordinates in millimetres. On screen there is
  no envelope. Its entire reason to exist is meaningless in HTML.
- `pageheader` has first-page/running cardinality that presupposes pages.
- `pdfaEnabled` is archival-only.
- The whole two-pass machinery exists only because pages exist.

And there's a sharper version of the argument: **the moment you accept a
`sys.format` conditional, you have conceded the artifacts differ.** You're only
choosing to express that divergence as branches threaded through one document rather
than as two documents. Thread it through one and every template becomes a union of
two designs, the editor canvas shows neither faithfully, and "one template, many
media" quietly degrades into "one template with two templates inside it".

This is also already supported. `TemplateVariant` exists. `referencedPaths` gives
contract-compatibility checking across both. Nothing needs building.

### The case for one template

A headcount. Of ~25 node renderers, exactly **four** are page-category (`pagebreak`,
`pageheader`, `pagefooter`, `addressblock`). The other ~21 — text, container,
columns, table, datatable, image, qrcode, separator, loop, conditional, stencil,
placeholder, datalist — are medium-neutral.

Two templates means duplicating 21/25 of the authored surface in order to vary 4/25,
and then keeping them in sync by hand, forever. When legal changes a paragraph, you
edit it twice — and one day you won't. The letter and the email then say different
things. That failure mode is arguably worse than any design compromise.

If this side wins, the honest claim is **not** "one template, many media". It's
"one source of truth, where the divergence is explicit, reviewable and diffable
instead of implicit across two documents". That's a narrower and more defensible
claim, and it's the one worth arguing.

### A third option worth pricing before either

**Shared stencils, separate templates.** Stencils already give us reusable authored
fragments with parameters ([`docs/stencils.md`](stencils.md), ADRs 0001–0003). Two
templates composing the same stencils gets sync-by-construction on the shared
content, plus full design freedom per medium — with **no `sys.format`, no IR, and no
renderer work at all**.

This is by far the cheapest option on the table, and if it's good enough then
everything below is unnecessary. The likely objection is granularity: stencils are
probably too coarse for "swap this one image for a link", and the skeleton still gets
duplicated. But it should be rejected deliberately rather than by omission.

## Vocabulary: format vs channel

Worth separating regardless of Fork 0, because they're different things and
conflating them is how this gets expensive.

- **Format** — how one letter renders: PDF, HTML, plain text.
- **Channel** — what artifact gets _assembled_: an email is an HTML rendering + a
  text rendering + an optional PDF attachment + envelope metadata, packaged as
  `message/rfc822`.

**A channel is composition, not a fourth renderer.** And it lands cheaply, because
the storage and delivery model already accommodates it:

- `documents.content_type` is per-row and generic — its comment literally says "MIME
  type of the generated document".
- `ContentKey.document(tenant, docId)` → `documents/{t}/{d}` carries **no extension**.
- `ContentStore.put(key, content, contentType, sizeBytes)` already takes a content type.
- Retention partitioning is `PARTITION BY RANGE (created_at)` — format-blind.

So a `message/rfc822` artifact is _just a document_. Storage, retention and download
need approximately nothing. Given the scope decision that **Epistola renders and the
host delivers** — no SMTP, no delivery state, no bounce handling — there's no new
lifecycle and therefore no new tables. This is the cheapest part of the whole
picture, and it's worth saying so plainly.

One thing it does imply: a channel means _three renders for one request_, assembled
into one document. That preserves the one-request-one-document invariant, but it
turns the executor's "render" step into "render several, assemble one" — which is
precisely the point at which a renderer abstraction earns its keep. A channel is a
composite backend.

### Format as a render parameter, not a variant attribute

Stated as a consequence rather than a verdict: each `TemplateVariant` owns its own
`TemplateVersion`. So modelling format as a variant attribute **duplicates the
authored document per medium** — which is the opposite of what one-template is for.
If Fork 0 lands on two templates, format-as-variant is exactly right. If it lands on
one, format has to be a render-time axis.

## The renderer seam

Three options. The thing worth optimising for is that PDF and HTML must never
disagree about **which content appears** — a styling difference is a bug, a content
difference is an incident.

### (a) Generify `NodeRenderer<T>`

Make the registry and renderers generic in the element type. Because
`ConditionalNodeRenderer` and `LoopNodeRenderer` carry no iText logic, they become
**literally shared code** — one implementation, every backend. The drift risk is
eliminated by a type parameter.

- _Cost_: `RenderContext` splits into a neutral core plus backend-typed state (only
  two fields move); the type parameter spreads through signatures.
- _Note_: this is on the path to (b), not opposed to it.

### (b) A resolved-document IR

Resolve expressions, conditionals, loops, stencils and styles into a medium-neutral
tree; backends consume it. Conceptually the cleanest, and it buys inspectability,
golden tests, and resolve-without-render.

**But there is a specific finding that complicates it** — the sharpest thing this
survey turned up.

`TwoPassAnalyzer.validate()` enforces two rules
(`modules/generation/.../pdf/TwoPassAnalyzer.kt:49`):

1. Two-pass expressions (`sys.pages.total`) must not appear in `FLOW_AFFECTING_TYPES`
   (`conditional`, `loop`, `datatable`).
2. Page-scoped expressions (`sys.pages.current`) must appear only inside
   `pageheader`/`pagefooter` **or their descendants**.

Rule 1 does **not** cover `sys.pages.current`. So a `conditional` whose condition is
`sys.pages.current = 1`, placed inside a `pageheader`, is **legal today** — it passes
rule 1 (wrong pattern) and rule 2 (it's a page-scoped descendant).

Which means **the header subtree's structure legally varies per page.** It cannot be
resolved once. That's why `RenderContext.withPageParams(pageNumber, totalPages)`
exists and is called per page from inside an iText event handler.

So a truthful IR is not "one resolved tree". It is:

- **body** — structurally resolved, but with leaf values possibly _deferred_ over a
  closed set of late-bound params (`sys.pages.total`);
- **bands** — not resolved at all: a template subtree plus a per-page resolve callback.

The honest conclusion: **resolve is not a phase, it's a function that backends call
on regions.** That's architecturally fine — arguably better — but it isn't the tidy
IR story, and it deflates much of the IR's appeal, because what you'd pass between
the phases starts to look like… `RenderContext` plus a node id. Which is today's
design.

By contrast, the case _against_ the IR is not band auto-grow. Band auto-grow does a
dry iText layout to measure content height (`HeaderFooterBand.kt`), and
`bodyContentTopPt` feeds a measured header height back to the address block — but
both are **geometry-only** feedback. They change where things sit, not which nodes
exist or what they say. The useful test: _does the layout feedback affect which nodes
exist and what values they carry, or only geometry?_ Geometry-only is backend-local
and harmless. Page-scoped params are the case that isn't.

### (c) Two parallel renderer trees

Cheapest to start and requires no refactor. The whole weight of the argument against
is drift — and note it's the _same_ failure mode as the two-templates fork, one level
down: two things that must agree, kept in sync by hand.

## `sys.format`

It fits the existing machinery almost for free: register a descriptor in
`SystemParameterRegistry` and add it to `buildGlobalParams`. It also passes the
`TwoPassAnalyzer` gate cleanly — `sys.format` isn't a two-pass pattern, so it's legal
inside `conditional` and `loop`, which is exactly where it needs to work.

**One conceptual wrinkle worth noticing.** Today `sys.*` means "a runtime value the
engine _discovers_" — page numbers, render time. Format isn't discovered; it's a
**dispatch axis the caller chooses before rendering starts**. The symptom is
`SystemParameterDescriptor.mockValue`: a mock for `sys.pages.total` makes sense (the
editor can't know it), but a mock for `sys.format` is nonsense — the editor _knows_
which format it's previewing and must never mock it. The first descriptor written
will have a field that must be ignored. That's a smell, not a blocker; a
`DISPATCH` scope alongside `GLOBAL`/`PAGE_SCOPED` would resolve it cheaply.

### Is a conditional on `sys.format` the right primitive?

**For:** zero new concepts — reuses expression evaluation, the registry, the
analyzer, and the editor's existing conditional UI. It composes
(`sys.format = 'html' and data.customer.optedIn`). One primitive covers branching a
word, a block, or a whole page.

**Against:** the CSS analogue is a `visibleIn: ['pdf','html']` prop, and our style
model is CSS-derived throughout — `@media print` is the idiom authors already know.
Consider the concrete case, "in HTML, replace the signature image with a link":

- With conditionals: two `conditional` nodes, each wrapping a slot, four nodes and
  two expression strings, nested two deep.
- With `visibleIn`: two sibling nodes with one prop each. Flat.

It's also stringly-typed: `sys.format == 'htlm'` silently evaluates false and the
content vanishes with no error, whereas an enum-array prop is validated at edit time.
For a dispatch axis with a **closed, engine-owned value set**, giving up static
checking to reuse a general expression evaluator is a real trade. And `visibleIn` is
prunable — a pure structural filter applied before any evaluator runs, which also
means you could compute "which formats does this version support" statically. You
cannot do that from a JSONata string.

These are not exclusive. `sys.format` is the general mechanism; `visibleIn` is sugar
over the 90% case. If both are wanted, the order matters less than not presenting the
first as the final answer.

### Consequences worth knowing

- **`referencedPaths` does not become format-dependent.** It's extracted statically
  from the whole `TemplateDocument`, walking all nodes rather than a resolved branch —
  so it's already a superset over all formats. Contract publish-impact checking stays
  conservative and correct (it may over-report a path only reachable in an HTML-only
  branch; over-reporting is the safe direction). No change needed.
- **`PageHeaderCardinalityValidator` survives unchanged**, and that's arguably the
  right answer. Its own comment says it "only guards the shape so the renderer's
  invariants hold" — it's a _structural_ claim about the document, not about any
  medium. A document with two page headers is still well-formed; an HTML backend just
  ignores one. Keeping validation format-agnostic matters: the moment it becomes
  format-dependent, you need to know at publish time which formats a version targets,
  and you've re-invented format-as-variant.
- **The editor canvas is the scoping question nobody asks early enough.** Preview and
  canvas are different things. _Preview_ already funnels through
  `DocumentPreviewRenderer.render(...)` → `ByteArray`; adding a format switcher is
  easy. The _WYSIWYG canvas_ is a paged A4 authoring view — making it render
  continuous HTML means a second canvas engine, which is a product, not a feature.
  Declaring "the canvas stays a print-shaped authoring view, and format-conditional
  subtrees get a badge" is probably the difference between a month and a quarter.

## Paged concepts in a continuous medium

Some of this is genuinely cheap. `pagebreak` → `break-before: page`, `keepTogether` →
`break-inside: avoid`, `keepWithNext` → `break-after: avoid` are exact CSS analogues:
inert on screen, correct if the user prints the page. (Caveat: browser support for
`break-after: avoid` in normal block flow is patchy — semantically right, practically
close to a no-op. Fine for continuous HTML; don't expect fidelity when printed.)

The rest are decisions, not defaults:

- **`pageheader`/`pagefooter`** — _which_ header? We permit two (first-page and
  running). In continuous mode there is no page 2, so the running header is
  unreachable content: render it and it appears once and is semantically wrong; drop
  it and we silently discard authored content. And the header is exactly where
  `sys.pages.current` tends to live.
- **`addressblock`** — "render as a normal block, drop the mm positioning" gives the
  component two unrelated semantics: "envelope window content" and "some text at the
  top". That's not obviously wrong, but it _is_ direct evidence for the two-templates
  side of Fork 0, and belongs there rather than buried in a degradation table.
- **Watermark** — easy to forget, and it bites first. `DocumentPreviewRenderer` stamps
  every preview via `WatermarkEventHandler`, per page, through the PDF event system.
  HTML has no event system, and editor preview is the _cheapest place to expose HTML
  first_ — so this gap lands immediately. (A fixed-position CSS overlay is the obvious
  answer.)
- **`pdfaEnabled`** — a template-level, PDF-only flag that already exists. Precedent
  that templates can carry format-specific _config_ without format being a template
  property. Also meaningless on a template rendered to HTML.

### What does "Page 1 of 5" do in HTML?

This needs an answer written down, and there's a candidate that costs nothing.

The claim that `SystemParamScope.PAGE_SCOPED` "already models unavailability" is
**a stretch worth resisting.** It models _where in a PDF document_ a param is
available — not _in which medium_. Different axis. And `sys.pages.total` is `GLOBAL`,
meaning "available in all contexts" — but under HTML it's available in _none_. So
either `GLOBAL` stops meaning what it says, or scope (where in a document) and
availability (in which media) are two orthogonal dimensions currently squeezed into
one enum. Conflating them produces exactly the bug that's invisible until a
customer's letter reads "Page of ".

The options: render empty (silent data loss into customer-visible output); fail the
render (honest, but makes HTML opt-in per template); make the author guard it with a
`sys.format` conditional (correct, but fails _open_ if forgotten).

Or — reuse `RenderMode`. We already have `STRICT` ("fail on missing resources") versus
tolerant modes that degrade to a placeholder. **An unavailable system parameter is
precisely a missing resource.** If each backend declares which system parameters it
provides, referencing an unprovided one routes through the existing policy: `STRICT`
fails loudly in preview and the editor; tolerant degrades gracefully in bulk
production. Zero new machinery, and it makes the answer to "what does Page 1 of 5 do
in HTML" a _template bug, surfaced loudly, with a documented fix_.

## Units

Two decisions that are easy to smuggle into one.

**Storing `pt` in the model: settled.** Migrating every stored `TemplateDocument` to
change units would be large risk for no user-visible benefit, and it would break the
determinism guarantee for published versions by rewriting a frozen artifact.
Non-starter. `sp` resolves to pt via `spacingUnit` and is invisible to a backend;
`mm` margins are paged-only and simply don't apply in continuous mode.

**Emitting `pt` in HTML: a separate call.** The model storing pt doesn't oblige the
backend to emit pt. And CSS `pt` is _absolute_ — it scales with browser zoom but not
with the user's default font-size preference, which is a WCAG 1.4.4 (Resize Text)
risk. That sits awkwardly next to a codebase that hand-builds PDF outlines for
accessibility (`RenderContext.bookmarkCollector` exists specifically for WCAG PDF2).
`RenderingDefaults.baseFontSizePt` makes pt → rem a division. A plausible split:
convert font-size to `rem`, keep pt for borders and spacing where absolute is
arguably correct.

Email is the exception, not the rule — email clients genuinely do want absolute units
and inline styles. But that's a _channel_ concern, and an emission profile is exactly
the kind of thing a channel layer should own. Worth not letting Outlook set the
default for the web.

## Where format lives in the request

If format is a render-time axis, the natural home is a `format` column on
`document_generation_requests` (forward migration, `NOT NULL DEFAULT 'pdf'` — the
default is safe forever, since a caller who doesn't know about formats means PDF).

Not on `documents` — `documents.content_type` already carries it. Though note they're
not the same fact: `format` is the _request axis_, `content_type` is the _artifact
type_. They're 1:1 today but won't always be (PDF/A and PDF are both
`application/pdf`).

Consequences to price rather than discover:

- **Data duplication.** Two requests for one letter = the `data` JSONB stored twice,
  contract-validated twice, template resolved and rendered twice. That's the price of
  the one-request-one-document invariant. Probably worth paying; worth measuring.
- **The collect feed gives consumers two results per letter.** This has the largest
  external blast radius — but it lands better than feared. `generation_results.content_type`
  already exists, so consumers can discriminate with no schema change. And
  `correlation_id` is already indexed **non-uniquely** on `(tenant_key, correlation_id)`,
  so two requests sharing a correlation id is an already-supported shape. **That makes
  `correlation_id` the grouping key for "these two documents are the same letter",
  working today with no migration.** Critically, it's safe by construction: nobody
  gets two results unless they asked for two formats.
- **`batch_id` semantics.** 100 letters × 2 formats = 200 rows in one batch. Progress
  reads "200 documents", which is arguably correct and definitely a UX surprise.
- **`filename`.** Client-provided and nullable. Two requests sharing a correlation id
  and a client-supplied `"invoice.pdf"` produce two documents both named
  `invoice.pdf`, one of which is HTML. Needs a rule: derive the extension from format
  when absent; reject or warn on mismatch.

## Determinism — the open question

This is the least-resolved part of the picture, and it's worth deciding before
anything else.

The pinning apparatus — `resolvedTheme` frozen at publish, `RenderingDefaults.forVersion`,
`FontSnapshotVerifier` ("Deterministic-or-nothing: a published version must render
with the exact font bytes pinned at publish") — exists so a published version renders
identically forever. `RenderingDefaults`' own rules say: never modify an existing
version; old published versions keep rendering with their original defaults.

The problem: a version published **today** pins defaults that contain nothing
HTML-specific, because HTML didn't exist. When an HTML backend ships and someone
renders that already-published version to HTML, it needs defaults the pinned version
doesn't have — and the rules forbid adding them retroactively. Three options:

1. **`HtmlRenderingDefaults` with its own pinned version axis.** Impossible for
   already-published versions — there's no HTML pin to retrieve, and backfilling one
   is fabrication, not pinning.
2. **HTML defaults resolved at render time from CURRENT.** Simple and honest, and
   **explicitly gives up determinism for HTML**: a version renders to different HTML
   across Epistola upgrades.
3. **Version the whole backend-defaults tuple going forward**, with pre-HTML versions
   falling back to (2).

The case for (2) is that determinism is a _PDF/archival_ promise — it exists for
PDF/A-2b, legal retention, and "what exactly did we send in 2026". HTML is a display
medium consumed immediately.

**But the answer depends on a question this document can't settle:** documents
_expire_ (retention, range-partitioned by `created_at`). **Is our archival record the
stored artifact, or the re-renderable version?** If the stored artifact is the record,
determinism only matters until expiry and the asymmetry is fine. If re-rendering from
the pinned version is the recovery path, then non-deterministic HTML means an expired
HTML document is _unrecoverable_ — you can produce _an_ HTML, not _the_ HTML. That
answer decides between (2) and (3), and it should be answered explicitly rather than
inherited.

Mechanically, one thing is clear regardless: `engineVersionString()` =
`"epistola-gen-$version+itext-$ITEXT_VERSION"` currently lives **on `RenderingDefaults`**
(`RenderingDefaults.kt:166`) and hardcodes iText. A defaults bag shouldn't know the PDF
library version; that belongs to a backend. Small, clean refactor — with one thing to
check first: `engineVersion` flows into `PdfMetadata` and possibly out through the API
or `generation_results`. If any consumer parses `+itext-` out of it, the format is de
facto public.

## The inverse architecture: HTML as the primary IR

Worth taking seriously, not least because our own docs already describe it.

**The case for:** we have a normalized node graph and CSS-derived styles. HTML+CSS is
a battle-tested IR with a spec and an ecosystem. Emit HTML once; get PDF from it via
pdfHTML or headless Chromium. One traversal, one style model, real CSS (grid,
flexbox, `@page`) instead of hand-mapping to iText primitives. The direct iText
renderer is thousands of lines of layout engine we maintain ourselves; deleting it
would be the single biggest simplification available, and it makes multi-format
trivial rather than a programme of work.

**The case against, which looks decisive:**

1. **It inverts the determinism guarantee into a determinism impossibility.**
   Chromium ships every four weeks and changes layout. Pinning a browser inside a
   deterministic render means shipping Chromium in the artifact and never upgrading
   it — including for CVEs. pdfHTML is steadier but its layout still evolves across
   releases with no pinning story. This trades our strongest architectural property
   for CSS features.
2. **PDF/A-2b.** We get it via `PdfADocument` + explicit ICC output intent + font
   embedding. Reliable PDF/A-2b out of Chromium is a post-processing gamble.
3. **Things that don't exist in HTML.** `AddressBlockEventHandler` draws at absolute
   page coordinates _after_ layout, relative to the _measured_ header height. Per-page
   watermarks. `PdfOutline` for WCAG PDF2. Band auto-grow via dry layout. All are
   post-layout PDF-object manipulation. Via HTML you either lose them, or you go
   HTML → PDF → _reopen the PDF with iText to fix it up_, which is strictly worse than
   what we have now.
4. **Deployment.** Single fat jar, Helm chart — and we already have a fat-jar
   classloader deadlock scar (JEP 491 / virtual threads, #724 — `JobPoller` runs on
   platform threads _because of it_). Adding a Chromium process is a new operational
   surface.

**Where it wins, and this is a real permanent tax:** hand-mapping CSS to iText means
we will never have grid or flexbox, and every new style property is a
`StyleApplicator` change. That cost never goes away.

The thing to be careful about is the reasoning: we shouldn't do this _because the docs
say so_. The docs are a fossil of a design that was abandoned — presumably for reasons
1–4. And sunk cost is not a reason to keep the direct renderer; but the _properties it
bought_ — determinism, PDF/A, accessibility control — are, and those are what Epistola
sells.

## If we wanted a cheap first step

Not a plan — an observation about what's available if this is ever picked up.

**The contract constraint is smaller than it looks, but not where you'd think.**
`previewDocument` returns `ResponseEntity<Resource>`, which is already
format-agnostic — so the _response_ side needs no contract change. But the _request
body DTO is generated from the `epistola-contract` repo_, so adding a `format` field
to it **is** a cross-repo publish and version bump. The response half escapes; the
request half doesn't.

The real escape hatch is different and better: **`epistola-mcp` is not
contract-governed.** Its tools live in this repo, and `PreviewResult` already has a
`mediaType` field (currently hardcoded to `"application/pdf"`). The editor preview
routes are HTMX/internal, also in-repo. So the seam, a backend, and end-to-end
exposure through MCP and the editor preview could all be built **without touching
`epistola-contract` at all** — with the REST `format` field added last, once the
backend is proven. That's also where you'd want HTML first anyway.

Roughly in order of increasing commitment:

1. **Fix the download bug** (see below) — correctness, independent of all of this.
2. **Write the real content type into `documents`** instead of the constant.
3. **`DocumentPreviewRenderer.render(...)` returns an artifact carrying its own
   content type** rather than a bare `ByteArray`. Internal. This is the port.
4. **Move `engineVersionString()` off `RenderingDefaults` onto a backend** — isolates
   the iText leak and forces the determinism conversation.
5. **Extract the neutral resolve context; generify `NodeRenderer<T>`.** Pure refactor,
   one backend, no behaviour change — covered by the existing golden PDF baselines.

**On a walking skeleton:** a `text/plain` backend is a better probe than a debug-JSON
dump, for two reasons. It isn't throwaway — a channel needs a text part for
`multipart/alternative`, so the skeleton proving the seam is the same code that ships.
And it's a _harsher_ test: with no styles at all, it forces every place a style
assumption leaked into structure to surface. HTML is too forgiving to catch that —
it'll happily accept a leaked `pt` and look fine.

## Findings that are wrong today

Recorded here, not filed. Both are independent of everything above.

**1. `downloadDocument` serves the wrong content type.**
`modules/rest-api/.../EpistolaDocumentGenerationApi.kt:164` hardcodes
`.contentType(MediaType.APPLICATION_PDF)` while `metadata.contentType` sits unused two
lines above — and `metadata.filename` is used on the very next line. It's only latent
because everything is PDF today; the moment `documents.content_type` is anything else,
downloads serve the wrong type. This is a correctness fix on its own merits,
regardless of whether any of this document survives review.

**2. `docs/generation.md` and `docs/roadmap.md` describe software that doesn't exist.**
The `HtmlRenderer` diagram, the Output Formats table, the per-renderer performance
figures, and roadmap lines 7 and 30 ("PDF and HTML", "HTML output, PDF via Puppeteer")
are all fiction, and the Puppeteer claim contradicts the shipped architecture. They are
left in place for now, but they carry a live cost: while they stand, every future reader
— including future us — will re-litigate the
[inverse architecture](#the-inverse-architecture-html-as-the-primary-ir) from a
document that reads like a decision but was only ever an aspiration.

## Related

- [`docs/generation.md`](generation.md) — current generation architecture (**see caveat above**)
- [`docs/stencils.md`](stencils.md) — reusable authored fragments, relevant to Fork 0
- [`docs/pdfa.md`](pdfa.md) — PDF/A compliance, relevant to the determinism discussion
- [`docs/fonts.md`](fonts.md) — font resolution and determinism
