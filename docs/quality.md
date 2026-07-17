# Quality checks

A generic, extensible system for surfacing problems in templates — readability, grammar,
cross-template consistency, and human review notes — in a tenant-wide report and (soon)
directly in the template editor.

**The suite does not run quality checks. It owns a ledger of findings.** Sources submit
findings and the ledger owns them from then on. That distinction drives everything below.

Checks only ever analyse a template's **example data**
(`contract_versions.data_examples`) — never real user data.

## Three needs, one write model

The feature is asked for three different things, and it is worth being explicit that they are
**three read surfaces over one ledger** rather than three systems:

| Need                                         | Surface                                       | Status  |
| -------------------------------------------- | --------------------------------------------- | ------- |
| "Tell me about the draft I am editing, now"  | Editor panel, checked on save                 | not yet |
| "Show me everything wrong across the tenant" | The report page, filtered by catalog/template | built   |
| "Is our quality getting better or worse?"    | Daily snapshots, a trend                      | not yet |

Nothing about the ledger changes to serve any of them: the same finding, submitted once, is
what the editor marks on the canvas, what the report lists, and what a snapshot counts. When
adding a surface, add a read — not a second write path.

Sources vary in where they run, and the ledger deliberately does not care:

| Kind                              | How it arrives                                                                                                                              |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Embedded, synchronous             | `QualityFindingSource` (in-process, ~1ms)                                                                                                   |
| Suite-initiated call to a service | also `QualityFindingSource` — a source may make an HTTP call inside `check()`; keep it to the sweep/publish triggers, never the editor path |
| Run elsewhere entirely (e.g. hub) | implements nothing; pushes over the REST ingest                                                                                             |
| A colleague's review remark       | `RecordManualFinding`                                                                                                                       |

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

The module reads core through core's **queries** (`GetEditorContext`, `ListDocumentTemplates`,
`ListVariants`) rather than core's tables, so a core migration can't silently break the sweep.
The one exception is tenant enumeration in `QualityCheckScheduler`, which reads `tenants`
directly: `ListTenants` is `RequiresAuthentication`, but the sweep enumerates tenants precisely
in order to bind a per-tenant system principal, so it has none to offer yet. `BackupScheduler`
does the same, for the same reason.

Typed keys (`QualityFindingKey`, `QualityFindingCommentKey`) and `KnownFeatures.QUALITY` stay in
core, mirroring `FeedbackKey` — feedback is likewise its own module.

The module ships its **own UI** (`ui/QualityHandler` + `templates/quality/`), following
`epistola-support-feedback`: Spring merges classpath templates from every JAR, so a feature
module hosts its handlers and templates while the host app keeps owning the chrome
(`layout/shell`, `layout/nav`). `QualityNavContributor` contributes the item into the host's
**Authoring** group — quality is about the templates being authored, and it is OSS, not
support-tier, so it does not belong under Support.

## The report page

`/tenants/{t}/quality` — every finding in the tenant, from every source. Filter by status,
severity, source and template; search messages; page. All of it is pushed to SQL, including
the filter on effective status, which is _derived_ (see [Ignores](#ignores)) — doing that in
Kotlin would mean fetching every finding in the tenant to render fifty of them, and a total
that disagreed with the rows.

It defaults to **open** findings. A report that opened on a wall of resolved ones would bury
what a reader can act on; "all" stays one click away, which is why an absent `status` param
and an empty one deliberately mean different things.

The page is where the ledger's two lifecycles become visible, and the UI states them rather
than blurring them:

- A **reconciling** source's finding offers no Resolve button. It closes when the source stops
  reporting it, and a Resolve action there would be a claim the next sweep silently overwrites.
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
  findings, nor a human's. Without it the last sweep to run would win.

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

- **No version.** An ignore carries forward across publishes. Key it per version and every
  publish resurfaces every previously-dismissed finding.
- **No finding row.** There is no FK. An ignore must outlive its finding (which
  auto-resolves) and be able to pre-exist a resurface.

`IGNORED` is **not a stored status** — it is derived from a live ignore row at read time.
That makes "an unchanged fingerprint retains its ignore" true by construction, with no
dual-write to diverge.

Ignores live here rather than in `TemplateDocument` for two independent reasons: the model
is a generated type from the external `epistola-model` contract, and published versions are
frozen — an ignore there could never be applied to a published version, which is exactly
where an author most needs one.

**Unignore soft-deletes** (`revoked_at`), and that is not tidiness. Sources read
dispositions with a `since` cursor; a hard `DELETE` would vanish from that feed, so a source
would never learn the ignore was lifted and would suppress the finding forever. Revoking
bumps `updated_at`, making a lift an observable event on the same feed that delivered the
ignore.

## Writing a source

### In-process

Implement `QualityFindingSource` and make it a `@Component`. The framework runs you on the
sweep, after a publish, and on the editor's "Check now". Being a bean _is_ the "runs
locally" flag.

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

### Remote

Implement nothing. Push your full finding set over the REST ingest and read dispositions
back with a `since` cursor. A remote checker needs no hub — an HTTP endpoint is enough.

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

## Triggers

| Trigger                                         | What runs                                         | Built   |
| ----------------------------------------------- | ------------------------------------------------- | ------- |
| `QualityCheckScheduler` (daily, `SINGLE_OWNER`) | Every variant of every tenant with the feature on | yes     |
| `OnVersionPublishedRunChecks` (`AFTER_COMMIT`)  | The published variant                             | yes     |
| Editor autosave and "Check now"                 | The open variant                                  | not yet |

All of them dispatch the same `RunQualityChecks`, so reconciliation, ignores and staleness
behave identically wherever a check was triggered from.

`RunQualityChecks` reads the **persisted** draft (falling back to the newest published
version), so an editor trigger is necessarily save-then-check — checking unsaved state would
be stale-in, stale-out. A consequence worth rendering explicitly when the panel lands: an
invalid draft will not save, so neither editor trigger fires on an unsaveable document.

**On wiring checks to autosave.** The editor flushes every few seconds, and the objection is
the obvious one: that is a lot of runs. It is nonetheless the intended design for
"semi-realtime" feedback, on two grounds — the in-process sources are ~1ms over an in-memory
node graph, and a finding is always "as of the last check" anyway, which is exactly what
`currentInputFingerprint` exists to make honest (see [Staleness](#staleness)). If an
expensive in-process source ever arrives, this is the trigger that has to change first, and
a run ledger (`(source, subject) → last input fingerprint`) is the thing to add. Remote
sources are never on this path; they schedule themselves.

The sweep is idempotent (a lease can expire and the occurrence be retried elsewhere), which
falls out of reconciliation being a full-set upsert.

## Configuration

| Property                         | Default        | Meaning                                           |
| -------------------------------- | -------------- | ------------------------------------------------- |
| `epistola.features.quality`      | `false`        | The feature toggle default (the feature is alpha) |
| `epistola.quality.sweep.enabled` | `true`         | Registers the daily sweep task                    |
| `epistola.quality.sweep.cron`    | `0 30 3 * * *` | When the sweep runs                               |

The feature is **alpha** (`FeatureStage.ALPHA`), so it renders an "Alpha" badge in the nav,
on its own page, and in the admin Features list. Alpha is the honest label here: the
ledger's semantics are settled and tested, but the surfaces on top of it are not, and no
remote source exists yet — so a tenant switching it on today gets the example source and
whatever they raise by hand. Expect the shape of the report, the editor panel, and the REST
ingest to move before this is stable.

The demo catalog's **`quality-showcase`** template exists to be found wanting: an empty block
and an overlong one, so the report has real findings to show. `DemoShowcaseQualityIntegrationTest`
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
to put it on the ledger. It lives in the **Upgrading** feature instead
(`epistola-support-snapshots` publishes a catalog snapshot to the hub, the hub renders it on
newer engine versions, `CompatibilitySyncPort` reads the verdicts back read-only). Four
differences, any one of which would bend the ledger out of shape:

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
inside the ledger at all. Tenant-level verdicts stay on the Upgrading page regardless.

## Permissions

`TEMPLATE_VIEW` reads; `TEMPLATE_EDIT` submits, ignores, comments and resolves. No dedicated
`QUALITY_*` permissions in v1 — they would ripple into `TenantRole.permissions()`, API-key
grants and their tests for an alpha feature. A dedicated pair is the natural refinement if
quality earns its own role.

No quality command calls `requireCatalogEditable`: a finding is metadata _about_ content,
not content. Ignoring a finding on a frozen published version, or raising a review note
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

## Backup

All three tables are **included** in tenant backup/restore (`QualityBackupTables`, the module's own contributor). Machine
findings ride along, but the human half — a stated ignore reason, a manual finding, a
comment — is authoring intent and must survive a restore.

The migration declares `forward=false`: these tables are backed up, so a backup taken after
it lists tables an older app lacks, and `RestoreTenantBackup.validateColumns` rejects a
manifest table absent from the live topology. (Contrast `audit_log`, which declares
`forward=true` only because it is _excluded_ from backup.)
