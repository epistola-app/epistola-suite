# Quality checks

> **Status:** Alpha, disabled by default (`epistola.features.quality`).
> Breaking changes to this surface may ship in a MINOR release.

A generic, extensible system for surfacing problems in templates — readability, grammar,
cross-template consistency, and human review notes — in a tenant-wide report and (soon)
directly in the template editor.

**The suite does not run quality checks. It owns a ledger of findings.** Sources submit
findings and the ledger owns them from then on. That distinction drives everything below.

Checks only ever analyse a template's **example data**
(`document_templates.data_examples`) — never real user data.

## Three needs, one write model

The feature is asked for three different things, and it is worth being explicit that they are
**three read surfaces over one ledger** rather than three systems:

| Need                                         | Surface                                       | Status  |
| -------------------------------------------- | --------------------------------------------- | ------- |
| "Tell me about the draft I am editing, now"  | Editor panel, checked on save                 | built   |
| "Show me everything wrong across the tenant" | The report page, filtered by catalog/template | built   |
| "Is our quality getting better or worse?"    | Daily snapshots, a trend                      | not yet |

Nothing about the ledger changes to serve any of them: the same finding, submitted once, is
what the editor marks on the canvas, what the report lists, and what a snapshot counts. When
adding a surface, add a read — not a second write path.

Sources vary in where they run, and the ledger deliberately does not care:

| Kind                              | How it arrives                                                                                                              |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Embedded, synchronous             | `QualityFindingSource` (in-process, ~1ms)                                                                                   |
| Suite-initiated remote work       | a run-scoped async request; the remote checker receives/fetches only declared inputs for that run and submits results later |
| Run elsewhere entirely (e.g. hub) | implements nothing; pushes over the REST ingest                                                                             |
| A colleague's review remark       | `RecordManualFinding`                                                                                                       |

**Compatibility drift is not on this list, and that is deliberate** — see
[Compatibility is not a quality source](#compatibility-is-not-a-quality-source).

## Where it lives

`modules/epistola-quality` — an OSS feature module depending on `epistola-core` +
`epistola-web`, **not** the commercial support tier (the ledger and its in-process sources work
with the tier off).

Outside core deliberately. Core must never call quality — the intended shape is that the
generation pipeline _emits_ and this module _subscribes_ — and having the dependency point this
way makes that a compile-time fact rather than a convention someone has to remember. It also
means the feature is genuinely droppable while it is alpha.

The module reads core through core's **queries** (`GetEditorContext`) rather than core's tables,
so a core migration can't silently break quality execution.

Typed keys (`QualityFindingKey`, `QualityFindingCommentKey`) and `KnownFeatures.QUALITY` stay in
core, mirroring `FeedbackKey` — feedback is likewise its own module.

The module ships its **own UI** (`ui/QualityHandler` + `templates/quality/`), following
`epistola-support-feedback`: Spring merges classpath templates from every JAR, so a feature
module hosts its handlers and templates while the host app keeps owning the chrome
(`layout/shell`, `layout/nav`). Quality is about a catalog's templates, so it is an item of the
catalog's own nav (`CatalogNav`, docs/workspace-shell.md), shown while the feature is available:
it opens the catalog's report, and the page passes the catalog as
`workspaceCatalog`, so the shell shows that catalog's bar and nav around it.

## The report page

`/tenants/{t}/catalogs/{catalog}/quality` — every finding against the catalog's templates, from
every source; a finding's page is `…/quality/{finding}`, in the catalog of its template. Like the
catalog's lists, there is no page of every finding in the tenant. Filter by status, severity,
source and template; search messages; page. All of it is pushed to SQL, including
the filter on effective status, which is _derived_ (see [Ignores](#ignores)) — doing that in
Kotlin would mean fetching every finding in the tenant to render fifty of them, and a total
that disagreed with the rows.

It defaults to **open** findings. A report that opened on a wall of resolved ones would bury
what a reader can act on; "all" stays one click away, which is why an absent `status` param
and an empty one deliberately mean different things.

The page is where the ledger's two lifecycles become visible, and the UI states them rather
than blurring them:

- A **reconciling** source's finding offers no Resolve button. It closes when the source stops
  reporting it, and a Resolve action there would be a claim the next source run silently overwrites.
- A **manual** finding (`reconciled = false`) offers Resolve, because nothing else will ever
  close it.

Ignoring requires a reason, and the error path is worth knowing about: the success path swaps
the whole detail body and disposes of the dialog with it, so a validation error is
`HX-Retarget`ed into the still-open dialog instead — otherwise the message would render
somewhere the reader can no longer see.

The template filter carries `catalogKey/templateKey` as a single value. A template key is
unique only within its catalog, so filtering on the key alone would also match a same-named
template in another catalog.

Reads are gated on `TEMPLATE_VIEW` and writes on `TEMPLATE_EDIT` by the commands and queries
themselves; the handler does not re-implement them. Only the **nav item** is gated on the
feature toggle — the routes stay registered, which mirrors feedback.

## The model

| Table                      | What it holds                                                                            |
| -------------------------- | ---------------------------------------------------------------------------------------- |
| `quality_findings`         | Every finding, from every source, including human ones                                   |
| `quality_finding_ignores`  | "This is a false positive, because…" — the only user-authored state a source cares about |
| `quality_finding_comments` | Discussion on a finding. Local-only                                                      |

Findings are **self-describing**: each carries its own `ruleId`, `severity`, `message` and
optional `docsUrl`. There is deliberately no local rule catalog — a remote source can add
or reword rules without a suite release, and there is no second thing to drift.

### message, message_code, context, metadata

Four columns that are easy to confuse, so worth stating plainly:

- **`message`** — the rendered text. Once `message_code` is set it is the default-locale
  fallback: shown as-is when the reader's locale has no bundle for the code.
- **`message_code`** — a stable i18n key, so the message can be re-rendered in another locale
  _without re-running the source_. Distinct from `rule_id`: `rule_id` is which rule fired,
  `message_code` is which message template to render — often 1:1, but a rule that emits
  materially different wording per case wants distinct codes. Null when a source does not
  localize (most do not yet); always null for a manual finding, which is prose.
- **`context`** — source-supplied **evidence**, shown to the reader under "Evidence" (e.g.
  `{"length": 879}`), and the interpolation params behind `message_code`. A locale render is
  `message_code + context`, never a re-parse of the English `message`.
- **`metadata`** — source-supplied **operational** data that is _never_ rendered: a remote
  checker's version or trace id, the suite version behind a PDF finding (Phase 5), a future
  hub-sync correlation id. The boundary against `context`: if a value explains the finding to a
  person it is context; if it would only confuse them shown as evidence, it is metadata. Two
  bags rather than one because they have different audiences and different UI treatment, and
  because Phase 5's writer of `metadata` is the _pipeline_, not the source.

The ledger never interprets `context` or `metadata`; both take the newest submission on a
resubmit, on the same row.

## Deletion: the database does it

A finding is metadata _about_ a resource and means nothing once that resource is gone, so the
database removes it (`V20261101000350__quality_2_0.sql`):

- **Template:** findings and ignores reference the template's identity,
  `catalog_resources (tenant_key, resource_id)` through `template_resource_id`, with
  `ON DELETE CASCADE`. The identity goes when the last version holding the template does
  ([ADR 0037](adr/0037-resource-identity-and-relocation.md)), so deleting a template from every
  version, or deleting its catalog or tenant, takes its findings and ignores with it. A template
  that only leaves a draft but stays in a release keeps them.
- **Variant:** no table holds a variant's identity across versions (it is an entry of its
  template's content, [ADR 0038](adr/0038-catalogs-in-2-0.md)), so a finding keeps its
  `variant_key` and the trigger `trg_release_resources_quality_findings_follow_variant` follows
  it: when a template's row in `release_resources` stops holding a variant (its content changes
  or the row goes) and no other row of that template, draft or release, still holds it, the
  variant's findings are deleted.

**Why foreign keys rather than an event handler.** They point quality → core, the same
direction the module dependency already points, so the cleanup costs nothing architecturally:
deleting a template never learns that quality exists, which is the invariant that keeps
this module droppable. An event subscription would be more code, eventually-consistent, and
able to fail.

Two subtleties, both pinned by `QualityCascadeIntegrationTest`:

- A **template-subject** finding has a NULL `variant_key`, so the variant trigger never matches
  it: such a finding survives a variant being deleted.
- **Ignores cascade too**, and that is a correctness rule rather than tidiness. URNs are built
  from slugs, so deleting `invoice` and creating a new `invoice` reproduces `ignore_scope_urn`
  _exactly_ — a reviewer's "does not apply here" from the deleted template would silently
  suppress findings on a template nobody has ever looked at. `quality_finding_ignores` carries
  `template_resource_id` for no other purpose: it is not part of the key, and is nullable so a
  future non-template scope simply is not cascade-bound.

## Anchoring a finding to elements

A finding carries `nodeIds` — the editor elements it is about. Each one gets a marker on the
canvas and in the structure tree; the first is where "go to" navigates (`primaryNodeId`).

It is a **list**, not a single node, because a finding is often genuinely about several
elements at once: two paragraphs that contradict each other, a set of blocks that disagree on
a date format, a heading inconsistent with its sibling. Emitting one finding per node instead
would split one problem into several — a reader would have to reassemble them, each would
have to be ignored separately, and one could resolve while the actual problem persisted.

Order is the source's own order of relevance: put the element an author should look at first,
first. An empty list is fine and means the finding is not about any particular element — a
document-level or data-level observation. Use `path` when there is a data location to point
at instead.

`nodeIds` is a **display field**: a source may add or drop an element without that being a new
problem, so a resubmit updates the set without reopening. If a changed element set _is_ a
different problem, that belongs in the fingerprint.

> **Catalog resource relocation.** `subject_urn` and `ignore_scope_urn` compose a template's
> _address_, and `ignore_scope_urn` is both a primary-key column and the finding-to-ignore join
> condition. Moving a template would leave both holding the old address while the next submission
> carries the new one, so every ignored finding would reappear as open. `OnResourceRelocatedRepointFindings`
> therefore rewrites both URNs, on findings and ignores, in the move's own transaction. It matches
> the moved address only where it ends the URN or is followed by `/`, so moving `letters/invoice`
> leaves `letters/invoice-v2` alone. Findings are read back by the template's identity; the URN is
> the match key for re-submission and the value on the wire. See
> [Catalog resource relocation](catalog-resource-relocation.md).

## Reconciliation: how findings resolve themselves

A source submits its **full current finding set** for a subject, not a delta:

- a fingerprint not seen before **opens**;
- one already open is **refreshed** (severity/message may change) and keeps its
  `first_seen_at` and its row id;
- one previously resolved **reopens on its original row**, so its comments survive;
- anything the source previously reported and now omits is **resolved**.

That last rule is the whole design. An author fixes a problem, the source stops reporting
it, and it closes with nobody clicking anything. No source tracks deltas; no UI needs a
"mark fixed" button for automated findings.

Two properties are load-bearing and easy to break:

- **An empty submission is meaningful** — it resolves everything that source had open for
  the subject. It needs no special case because `fingerprint <> ALL('{}')` is TRUE in
  Postgres. A "skip the UPDATE when the list is empty" optimisation would strand every open
  finding as OPEN forever.
- **Reconciliation is scoped by `source_id`** — a source can never resolve another's
  findings, nor a human's. Without it the last source submission would win.

## Fingerprints

Every source computes a `fingerprint` identifying the **problem**. The ledger treats it as
opaque. The contract:

> stable across re-runs while the same problem is present; different once the problem
> materially changes.

This is the part that is easy to get subtly wrong, and everything rests on it. Too volatile
(hashing the whole document) and every edit resurfaces every ignore. Too stable (just the
rule id) and an ignore silently swallows a genuinely different problem.

A reasonable recipe is `sha256(ruleId | subjectUrn | nodeId ?: path | normalized-evidence)`.
What counts as evidence is a judgement call — see `ExampleQualitySource` for a worked
example of both sides:

- `example.long-text` **excludes** the text. Editing a too-long block that stays too long is
  the _same_ problem, so it keeps its ignore. Shorten it past the threshold and it
  auto-resolves — that is the correct exit, not a new fingerprint.
- A rule where the text _is_ the problem (a misspelling) would **include** the word: fixing
  one typo and introducing another is a different problem and must not inherit the old
  ignore.

## Ignores

An ignore is recorded against `(ignore_scope_urn, source_id, rule_id, fingerprint)` — note
what is **not** in that key:

- **No version.** An ignore carries forward across edits and releases. Key it per release and
  every release resurfaces every previously-dismissed finding.
- **No finding row.** There is no FK. An ignore must outlive its finding (which
  auto-resolves) and be able to pre-exist a resurface.

`IGNORED` is **not a stored status** — it is derived from a live ignore row at read time.
That makes "an unchanged fingerprint retains its ignore" true by construction, with no
dual-write to diverge.

Ignores live here rather than in `TemplateDocument` for two independent reasons: the model
is a generated type from the external `epistola-catalog` contract, and released content is
frozen — an ignore there could never be applied to a release, which is exactly where an
author most needs one.

**Unignore soft-deletes** (`revoked_at`), and that is not tidiness. Sources read
dispositions with a `since` cursor; a hard `DELETE` would vanish from that feed, so a source
would never learn the ignore was lifted and would suppress the finding forever. Revoking
bumps `updated_at`, making a lift an observable event on the same feed that delivered the
ignore.

## Writing a source

### In-process

Implement `QualityFindingSource` and make it a `@Component`. The framework runs you when a
variant is marked ready and on the editor's "Check now". Being a bean _is_ the "runs locally" flag.

```kotlin
@Component
class MySource : QualityFindingSource {
    override val sourceId = QualitySourceId("my-checks")
    override val displayName = "My checks"
    override fun check(input: QualityCheckInput): List<SubmittedFinding> = …
}
```

A source is a pure function from `QualityCheckInput` to findings — it never touches JDBI, so
it unit-tests with a plain fixture and no Spring context. Return the **full set**; empty
means "nothing wrong", and correctly resolves what you had open. Don't throw for an ordinary
result: a source that throws is logged and skipped (its findings are left untouched rather
than reconciled away on the strength of a bug), but it costs the run.

`QualitySourceId.MANUAL` is reserved and rejected at startup.

### How a source gets extra data

Sources declare coarse data requirements; the runner resolves the union for the selected sources
and fills `QualityCheckInput` facets before calling them. The first implemented requirement is
`RESOLVED_TEMPLATE_DEPENDENCIES`, currently populated with font references discovered in the
checked template. It is intentionally a dependency family rather than a font-specific contract:
assets, themes, stencils, code lists and similar references belong in the same facet when real
sources need them.

An absent entry in a resolved facet means **unknown**, not broken. A source must stay silent on
unknown input so a resolver gap cannot manufacture findings.

Remote checks use the same requirement idea, but not the same call path. A remote run gets an
immutable manifest for one `run_id` and input fingerprint: small facts can be pushed inline, while
large artifacts such as rendered PDFs are exposed through short-lived run-bound handles. The remote
checker submits its full finding set later with the `run_id` / `input_fingerprint`; stale or failed
runs must not auto-resolve old findings.

Several ship today; these are worth reading in this order:

- **`ExampleQualitySource`** (`example`) — a reference implementation, deliberately trivial. Its
  job is to demonstrate the seam and the fingerprint contract, not to be a good check.
- **`AccessibilityQualitySource`** (`accessibility`) — a real one. It reports an `image` node
  with no `alt` that is not marked `decorative`, which is exactly the case where
  `ImageNodeRenderer` will stamp a **PDF/UA-1 identifier** on a document with no alternate
  description behind it. The rule mirrors that renderer, so the two must move together; it is
  also the first source to carry a `docsUrl`, since it can point at the WCAG technique rather
  than explain itself. It needs nothing but the template model, which is why it did not wait for
  the widened input.
- **`ComponentLimitQualitySource`** (`layout`) — reports a document holding more instances of a
  component than the registry's `maxInstancesPerDocument` allows; today that is a second address
  block, typically brought in by an included stencil. It is a finding rather than a save-time
  refusal on purpose: the author often cannot fix it from the template, and the document still
  renders with one address block.

### Remote

Implement no `QualityFindingSource`. A remote checker participates through a run/input/result
protocol: Epistola creates a run for a source, subject and input fingerprint; grants only the
declared inputs for that run; and later accepts a full finding set or failure for the same
`run_id` / `input_fingerprint`. Small input facts can ride inline; large artifacts such as rendered
PDFs use short-lived run-bound handles. Old results must never be stamped current, and a failed or
timed-out run must not auto-resolve previous findings.

A remote checker then reads dispositions back with a `since` cursor. A remote checker needs no hub
by definition — an HTTP endpoint is enough — though the hub is the likely first implementation.

> **Status:** the REST surface is not built yet, and is **deliberately deferred until a real
> remote checker exists to design against** — the wire format is the one part of this that is
> expensive to get wrong, since it lands in `epistola-contract` and becomes a compatibility
> obligation. (It is gated on a contract release regardless: `modules/rest-api` consumes
> generated server interfaces from that published artifact rather than a local spec.)
>
> The ledger already anticipates the shape, which is why deferring costs nothing: submissions
> are scoped by `source_id`, fingerprints are opaque to the ledger, and unignore soft-deletes
> precisely so a disposition feed can be polled with a `since` cursor. The likely first
> remote source is a hub compatibility check that names a specific template — see
> [Compatibility is not a quality source](#compatibility-is-not-a-quality-source).

### Manual

`RecordManualFinding` — a reviewer raising a note against the same subject, in the same
list, as the automated findings. It inherits the ignore flow, the report filters and the
node highlight for free.

**Manual findings do not reconcile.** An automated source submits a full set, which is what
lets its findings auto-resolve; a person submits one note and walks away, so there is
nothing to auto-resolve against. They need `ResolveManualFinding` and a human. The read
model exposes `reconciled` so the UI offers a Resolve action for exactly these — it is a
genuine second lifecycle, surfaced rather than hidden.

## Staleness

Checks run out of band, so a finding is always "as of the last check". The ledger stamps
each finding with an `input_fingerprint` — a hash of the document it was computed against —
and the editor compares that to the document live now to mark a finding outdated rather than
present a stale claim as current.

**The ledger computes it, never the source.** Postgres normalizes `jsonb` key order, so
`md5(template_model::text)` is stable server-side; a remote source hashing the JSON _it_
fetched would produce a different string for the very same document, and every finding it
submitted would read as permanently stale — silently, forever.

Manual findings carry no input fingerprint: a human's remark is not "computed against" a
revision and must never be marked outdated by an edit.

> **Proposed:** [ADR 0044](adr/0044-quality-results-keyed-by-content.md) replaces this stamp
> and, in steps, the reconciling ledger: check results become a cache keyed by the content they
> checked, and findings a view per catalog version derived from it.

## Triggers

| Trigger                                  | What runs                              | Built |
| ---------------------------------------- | -------------------------------------- | ----- |
| A save (`OnVariantSavedScheduleChecks`)  | The saved variant, a few seconds after | yes   |
| A ready mark (`OnVariantReadyRunChecks`) | The variant marked ready, at once      | yes   |
| Editor "Check now"                       | The open variant, in the request       | yes   |

All of them dispatch the same `RunQualityChecks`, so reconciliation, ignores and staleness
behave identically wherever a check was triggered from.

A save and a ready mark do not run the checks themselves: they schedule the variant's
quality-check **cluster timer** (`QualityCheckTimer`, [timers](timers.md)), one per variant. A
save sets it `epistola.quality.check-on-save-delay` out (5 s) and each later save moves it, so a
burst of autosaves is checked once, after the last; a ready mark sets it due at once. The timer row
is written in the save's own transaction (the handlers are `IMMEDIATE`), so a save that rolls back
leaves no timer, and the check runs later, on one node, as the tenant's system user. A save that
targets a draft other than the catalog's next version is not checked yet: the ledger keeps no
version (ADR 0044, section 8).

The ready trigger was an `AFTER_COMMIT` event handler until the language checks, and never ran:
the mediator invokes `IMMEDIATE` event handlers only.

`RunQualityChecks` reads the variant's **persisted** working model, so an editor trigger
is necessarily save-then-check — checking unsaved state would be stale-in, stale-out. A
consequence worth rendering explicitly when the panel lands: an invalid model will not
save, so neither editor trigger fires on an unsaveable document.

**No daily sweep yet.** A template that changes without a save (an import, a relocation) is checked
on its next save, ready mark or "Check now".

`RunQualityChecks` manages its own transactions (`SelfManagedTransaction`): a language checker may
be a server across the network, and no transaction waits on it. It returns what each source did:
checked, skipped (with a reason, such as LanguageTool not being configured) or failed, which
"Check now" shows in the panel.

## Language checks

> **Status:** Alpha, a pilot of [ADR 0044](adr/0044-quality-results-keyed-by-content.md); profiles
> ([ADR 0046](adr/0046-quality-profiles-and-rules.md)), providers ([ADR 0047](adr/0047-quality-providers.md))
> and locations ([ADR 0045](adr/0045-locations-in-a-template.md)) are proposed and not built.

Language checks read a template's **text**: spelling, grammar, style and readability. They are run by
**providers**, each a `TextChecker` bean and a quality source of its own, so one provider failing never
resolves another's findings.

| Provider       | Source id      | Where text goes                         | Runs on                                 | For a tenant                                             |
| -------------- | -------------- | --------------------------------------- | --------------------------------------- | -------------------------------------------------------- |
| Built-in rules | `builtin`      | nowhere: in-process                     | every trigger                           | always, with the quality feature                         |
| LanguageTool   | `languagetool` | a LanguageTool server the operator runs | save, "Check now", ready (configurable) | when configured, and switched on in the quality settings |

**What a provider sees.** The suite extracts each paragraph and heading of a template's `text`
nodes (`TextBlockExtractor`) as text with **placeholders**: an expression becomes one character
(U+FFFC), so a provider never learns which expression it stands for, let alone a value. Example data
and generated documents never reach a provider. A finding that falls on a placeholder is dropped. A
template holds stencil instances by reference, so a stencil's text is not checked as part of the
templates that use it.

**Rules.** The built-in engine runs **rules**: an id, a title, an intent (why), a severity and a check
type with its parameters (`BuiltinRule`, `BuiltinCheck`): repeated words, a sentence-length limit,
spacing, and word lists per language. Until quality profiles exist it runs
`BuiltinRuleSets.CLEAR_LANGUAGE`, the rules the default profile will hold, with the ids it will use:

| Rule                                      | Check                                                    | Severity |
| ----------------------------------------- | -------------------------------------------------------- | -------- |
| `clear-language.repeated-word`            | a word twice in a row ("dat dat", "had had" allowed)     | warning  |
| `clear-language.sentence-length`          | more than 25 words in a sentence                         | info     |
| `clear-language.double-space`             | two or more spaces between words                         | info     |
| `clear-language.space-before-punctuation` | a space before punctuation, except in French             | info     |
| `clear-language.plain-words`              | formal words with a plain alternative, Dutch and English | info     |

LanguageTool reports under its own rule ids until profile rules map onto it.

**Results are cached by content.** `quality_text_results` holds what a provider reported for a block,
keyed by the provider, its configuration (the built-in engine's version and the hash of its rules;
LanguageTool's server version and level), the locale and the hash of the block's text. A save is
therefore checked for the paragraphs it changed, and a paragraph shared by many templates of a tenant
is checked once. A changed configuration is a new key, so nothing is ever invalidated; the table can
be emptied at any time and is filled again. It is per tenant, so a result never tells one tenant what
another has written.

**Triggers belong to the provider.** A run carries the triggers that asked for it (a save, "Check
now", a ready mark; several when they were coalesced on the timer), and asks only the providers
configured for one of them.

**The language** is the variant's `system.locale`, else the tenant's default (`docs/locale.md`). The
built-in rules always run; LanguageTool runs only for a language someone chose for the template or the
tenant, never on the installation's default: a Dutch template checked as English would be flagged
word by word. A variant with neither says so in the panel ("no language is set").

**A finding** sits on the block's node, with its evidence in `context`: `quote` (the flagged text, as
the author wrote it), `before` and `after`, `category`, `suggestions` and `explanation` (the rule's
intent, or LanguageTool's description of its rule). The editor panel shows the quote and the
suggestions; nothing is applied for the author. The fingerprint holds the flagged text for spelling
and style, so another typo is another problem, and leaves it out for readability, so a reworded
sentence that is still too long keeps its ignore.

**Quality settings** (`/tenants/{tenant}/quality/settings`, under Settings, and linked from the
report) list the installation's providers for a tenant administrator (`TENANT_SETTINGS`): where each
sends text, when it runs, and a switch for the ones a tenant turns on. A provider the installation
did not configure shows why it has no switch. The choice is stored per tenant in
`quality_provider_settings`, with who changed it and when; no row means off.

**LanguageTool** is an installation provider: unused unless the operator sets
`epistola.quality.language-tool.url`, and then only for tenants that switch it on in their
**quality settings** (off by default), because template text is sent to it. It is called at
`POST {url}/v2/check` with LanguageTool's annotated text, each placeholder as markup it reads as the
word `X`; several paragraphs go in one request. If the server is down, its earlier findings stay as
they were and the run reports it as failed; the built-in rules run regardless.

## Configuration

| Property                                                | Default               | Meaning                                                                                    |
| ------------------------------------------------------- | --------------------- | ------------------------------------------------------------------------------------------ |
| `epistola.features.quality`                             | `false`               | The feature toggle default (the feature is alpha)                                          |
| `epistola.quality.check-on-save`                        | `true`                | Check a variant after it is saved                                                          |
| `epistola.quality.check-on-save-delay`                  | `5s`                  | How long after the last save the check runs                                                |
| `epistola.quality.language-tool.url`                    | empty                 | A LanguageTool server, such as `http://languagetool:8010`; empty: LanguageTool is not used |
| `epistola.quality.language-tool.level`                  | `default`             | LanguageTool's `level`; `picky` adds style rules                                           |
| `epistola.quality.language-tool.connect-timeout`        | `2s`                  |                                                                                            |
| `epistola.quality.language-tool.read-timeout`           | `20s`                 |                                                                                            |
| `epistola.quality.language-tool.max-request-characters` | `20000`               | Text per request; more is split over several                                               |
| `epistola.quality.language-tool.triggers`               | `SAVE,EXPLICIT,READY` | When LanguageTool runs                                                                     |

In the Helm chart, set them as environment variables under `env`, for example
`EPISTOLA_QUALITY_LANGUAGETOOL_URL`. To run LanguageTool locally:
`podman run -d -p 8010:8010 docker.io/erikvl87/languagetool` and
`--epistola.quality.language-tool.url=http://localhost:8010`.

The feature is **alpha** (`FeatureStage.ALPHA`), so it renders an "Alpha" badge in the nav,
on its own page, and in the admin Features list. Alpha is the honest label here: the
ledger's semantics are settled and tested, but the surfaces on top of it are not, and no
remote source exists yet — so a tenant switching it on today gets the example source and
whatever they raise by hand. Expect the shape of the report, the editor panel, and the REST
ingest to move before this is stable.

The demo catalog's **`quality-showcase`** template exists to be found wanting: an empty block,
an overlong one, a paragraph of language mistakes (`n-language`: a repeated word, "utilise",
"prior to", and a misspelling left for LanguageTool), an image with no alt text, and a decorative
image the accessibility source is right to stay quiet about — so the report has real findings to
show. Its variant names its language (`system.locale` `en-GB`), so a configured LanguageTool
checks it for a tenant that switched it on. `DemoShowcaseQualityIntegrationTest`
installs the bundled catalog and asserts exactly those, so the demo cannot quietly be tidied
into demonstrating nothing. `DemoLoader` turns the toggle on for the demo tenant and seeds the
human half too (a hand-raised finding, a comment, an ignore with a reason) — that half has no
source to produce it, and without it the demo would show only the machine side of a feature
whose point is that both share one ledger.

The feature key is `quality`, **not** `support-quality`, and is deliberately outside
`SUPPORT_TIER`/`HUB_ONLY`: the ledger and its in-process sources work with the support tier
off. A key in `SUPPORT_TIER` is gated on a hub entitlement, and the hub wire contract has no
`QUALITY` feature to grant — so a quality key there would be _permanently unavailable_ on
every installation running the tier. `KnownFeaturesTest` guards that; note
`WireContractAlignmentTest` would not (it asserts three named keys rather than iterating
`KnownFeatures.all`).

A future hub _transport_ for findings gets its own separate key rather than reclassifying
this one — renaming a feature key is a migration.

## Compatibility is not a quality source

"Template X renders differently on version Y" looks like a finding, and the first instinct is
to put it on the ledger. It lived in the **Upgrading** feature instead, which the hub fed from
tenant snapshots and which 2.0 withdrew with them. Should compatibility checks return, the same four
differences keep them off the ledger; any one of them would bend it out of shape:

|                | A quality finding                           | A compatibility verdict                |
| -------------- | ------------------------------------------- | -------------------------------------- |
| Subject        | a template/variant/version                  | `(tenant, catalog, targetVersion)`     |
| Who acts       | the author, by editing                      | the operator, by upgrading             |
| How it clears  | the source stops reporting it               | the installation moves to that version |
| Reconciliation | a full set per subject, so absence resolves | no set to be absent from               |

The last row is the load-bearing one. Auto-resolve exists because a submission is a source's
**complete** current set for a subject; a stream of per-version verdicts has nothing to
reconcile against, so every ledger mechanism built on that contract would be dead weight —
and "resolve by upgrading the installation" is not something an author can act on from a
template page.

**The bridge, when it is wanted:** if a hub compatibility check ever names a _specific
template_ ("the totals table shifts on 1.1.0"), that is genuinely an author-actionable
finding about a template, and it enters through the front door — the hub pushes it as an
ordinary remote source (say `rule_id = compat.render-drift`), with no compatibility concepts
inside the ledger at all. Tenant-level verdicts would stay off the ledger regardless.

## Permissions

`TEMPLATE_VIEW` reads; `TEMPLATE_EDIT` submits, ignores, comments and resolves. No dedicated
`QUALITY_*` permissions in v1 — they would ripple into `TenantRole.permissions()`, API-key
grants and their tests for an alpha feature. A dedicated pair is the natural refinement if
quality earns its own role.

No quality command calls `requireCatalogEditable`: a finding is metadata _about_ content,
not content. Ignoring a finding on released content, or raising a review note
against a read-only system catalog, must work — those are precisely the places an author
cannot "just fix it".

## Known limitations

- **Compatibility verdicts are a separate system, by decision** — see
  [Compatibility is not a quality source](#compatibility-is-not-a-quality-source).
- **Late submissions get stamped current.** A source that analysed a stale copy and submits
  afterwards has its findings stamped with the _current_ input fingerprint, so they read as
  fresh. The fix is an echo — the source returns the fingerprint it was handed and the ledger
  rejects a mismatch. Deferred.
- **Resolved findings accumulate.** Reconciliation never deletes (a cascade would eat the
  comments). Retention pruning is not built.
- **Comments are local-only.** A disposition has an obvious meaning to a remote checker
  ("stop reporting this"); a comment does not, and inventing an answer would bake it in. The
  finding id is stable, so a future sync has something to hang off.
