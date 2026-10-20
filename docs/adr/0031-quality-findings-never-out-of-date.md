# ADR 0031: Quality findings that are never out of date

- **Status:** Proposed
- **Date:** 2026-10-20
- **Deciders:** Epistola team
- **Tags:** quality, editor, maintainability, staleness, performance

## Context

The quality feature (alpha, [`docs/quality.md`](../quality.md)) is a ledger: sources submit
findings, the ledger stores and reconciles them, and the report and the editor read the stored rows.
A finding is therefore "as of the last check", and that check runs only on a trigger: marking a
variant ready, or "Check now" in the editor.

That leaves findings out of date, shown as current, in two ways:

1. **Nothing re-checks after an edit.** Change the template after the last trigger and the panel and
   the report show what was true before.
2. **The staleness stamp sees only the template's own model.** The ledger stamps a finding with
   `md5(template_model)`. A finding also depends on what the template uses: the stencils it
   instantiates, its data contract, its theme, the releases its catalog pins. Rename a stencil's
   parameter and every template's finding about that stencil is wrong, while the template's model,
   and so its stamp, is unchanged.

The second is not hypothetical. Stencil parameters are checked twice today: by the fit check, which
refuses marking a variant ready and releasing (`StencilInstances`, `StencilFitCheck`), and by
`StencilParameterQualitySource`, a second copy of the required-parameter rule that reads a parameter
schema snapshot stored in the template. The two can disagree, and the second goes stale.

Every source today (accessibility, component limits, stencil parameters, theme presets, unresolved
fonts, the worked example) is in-process and pure. The ledger is also meant for sources that are not:
remote checkers (ADR 0011), the hub's REST ingest, and a colleague's review note.

No one uses the quality checks yet, so the existing data can be deleted.

**Requirement.** No finding is shown as current when it is not, and it stays maintainable: one
definition of each rule, nothing kept in sync by hand.

### What "revision" means here

2.0 removed the **versions** of individual resources: a template or stencil has no version 1, 2, 3
of its own; the catalog is versioned as a whole. What remains is a **change counter**, a `revision`
column raised by one on every save that changes the row, used today for optimistic locking (the REST
`ETag`, the editor's conflict check). It is on templates, variants, themes, stencils, code lists and
attribute definitions (`core_content_revisions`), and on catalog versions (name, default theme,
presets and dependency declarations), tenants and environments (`core_settings_revisions`). It is not
ADR 0026's `resource_revisions`, the content-addressed snapshots releases are built from.

### Measured cost

A catalog of 300 templates, each with a stencil instance and a theme, on a local database, warm:

| Work                                                       | Per variant | 300 variants |
| ---------------------------------------------------------- | ----------- | ------------ |
| Load a variant's inputs (`GetEditorContext`)               | 1.6 ms      | 0.5 s        |
| Expand its stencils for the fit check                      | 1.5 ms      | 0.5 s        |
| A full stored check run: load, six sources, store          | 18 ms       | 5.4 s        |
| One query: which stored runs' inputs changed (18,000 rows) | —           | 4.4 ms       |

Local timings are indicative, not exact; the proportions are what matters.

## Options considered

### A. Keep storing; re-check on every relevant change

Every write that changes something a check reads triggers a re-check of the subjects it affects.

- Every change path has to remember to trigger, and to work out the affected subjects (a stencil
  change reaches every template that uses it). A path that forgets produces exactly the stale
  finding this is meant to remove, silently.
- Between the change and the re-check the stored finding is still wrong, and shown as current.

### B. Keep storing; stamp every input; mark outdated findings

As A, but each finding's stamp covers everything it was computed from, so a stale one is shown as
outdated. Honest, but it never makes a finding current: an author sees "outdated" until something
re-checks.

### C. Compute local findings when they are read, never store them

A check the suite runs itself is not stored: the panel, the report and the usage page compute
findings from the current state when they are opened. Remote, hub and manual findings stay in the
ledger as in B.

- Never stale for local checks, and no triggers to wire.
- **Pays on every read.** Loading the inputs is the cost, about 3 to 4 ms per variant before any
  check runs: about a second each time a 300-variant report opens, growing with the catalog. A cache
  helps less than it seems: its key is a hash of the inputs, which have to be loaded to compute it.
- **Conflicts with existing rules.** The report filters, sorts, counts and pages findings across a
  catalog. Computed findings do that in application code, which the rule "no in-memory pagination"
  forbids; a count on a catalog ("12 open findings") is a full computation.
- **Two models.** It splits the ledger's founding rule, one write model with several read surfaces
  (`quality.md`, "add a read, not a second write path"): every surface merges computed and stored
  findings, and ignores and comments need a second way to attach.
- **A forgotten input is a stale cache.** Installation-wide state (the fonts the server has, the
  rendering defaults) is easy to leave out of the key, and then a cached result is wrong.
- **No events and no history**: nothing can say "a new problem appeared".

### D. Hide an outdated finding until it is re-checked

Hides a real problem for as long as the re-check takes, and for good when a checker is down or a run
fails. A finding found yesterday is most likely still true after an unrelated edit.

### E. Store findings with an exact stamp of what they read (chosen)

Findings stay in the ledger. Each check **run** records the inputs it actually read, as (resource,
catalog version, change counter) pairs. Whether a finding is current is one query comparing its run's
stamp with today's counters. The same stamps tell which runs read a resource, so a change re-checks
exactly the subjects it affects.

- **One model.** Reconciliation, ignores, comments, history and events as today; filtering, sorting,
  counting and paging stay in SQL.
- **Cheap to read.** 4.4 ms for every run of a 300-variant catalog at once (measured).
- **Exact.** The runner records what it loads, so the stamp is what the check read, not what someone
  remembered to list; a check cannot forget an input it was given.
- **Pays on writes, in the background.** A re-check is a full run (18 ms per variant measured), after
  the change commits.

## Decision

**Option E.**

### The stamp

- A **check run** is one source run on one subject (a variant). The ledger keeps one row per run and
  its stamp: one row per input read, `(resource identity, catalog version, change counter)`. Runs,
  not findings, carry stamps, since all findings of one run read the same inputs: about ten rows per
  run.
- **The runner records the stamp**, not the source. Sources receive their data through the runner
  (ADR 0011's declared requirements) and stay pure functions of it; a guard test keeps a source from
  reading past the runner.
- **Inputs without a change counter get a token of their own:** a font or image by the content hash
  of what it resolved to, installation-wide state (the fonts the server has, the rendering defaults)
  by the application's version. Each is listed explicitly where it is added.
- **A pinned release never changes**, so an input read from one is not compared; changing a pin
  raises the catalog version's counter, which is in the stamp.
- **A copy into another draft keeps its counter**, so the stamp names the catalog version an input
  was read in.
- **A deleted input** makes the run outdated.

### Current, outdated, re-checked

- **Current** when every input of the run is at the counter it was read at; one query answers it for
  a whole report page.
- **Outdated, never hidden.** Otherwise the finding stays visible, muted, and says what changed (for
  example "stencil `letterhead` changed since this was checked"). A manual note has no run and is
  never outdated.
- **Re-checked when an input changes.** After commit, the runs that read the changed resource are
  found by their stamps and re-run, coalesced so a stencil saved many times re-checks each subject
  once (ADR 0012's single-flight and latest-wins). Local sources are re-run eagerly; a remote one is
  marked outdated and re-run on its own schedule. The planned periodic sweep is the backstop.
- **Gates.** The fit check stays where it is, blocking marking ready and releasing from the current
  state; it becomes the quality source for stencil fit, so the report and the refusal are one rule,
  and `StencilParameterQualitySource` is removed. A blocking recorded source would count an outdated
  finding as unchecked; none exists yet.

### What is stored where

Four findings on one variant, `letters/invoice/main`, and what each leaves in the database.
`quality_check_runs` and `quality_check_run_inputs` are new; the rest exist.

**1. Stencil fit (local): "binds `company`, which stencil `letterhead` no longer declares".**

| Table                      | Row                                                                                                                                                     |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `quality_check_runs`       | run 41: subject `letters/invoice/main`, source `stencil-fit`, ran 10:02                                                                                 |
| `quality_check_run_inputs` | run 41 read: variant `main` @ next version, counter 7; template `invoice` @ next, 4; stencil `letterhead` @ next, 3; catalog version `letters` next, 12 |
| `quality_findings`         | source `stencil-fit`, rule `probably-renamed-parameter`, fingerprint `…`, node `inst`, message, status open, run 41                                     |

Saving `letterhead` raises its counter to 4: run 41 is outdated (one query), it is found as a reader
of `letterhead`, and it re-runs after commit. If the template now binds the new name, the finding
resolves; otherwise it stays open, stamped by the new run.

**2. Unresolved font (local): "uses font `Fira Sans`, which this installation does not have".**

| Table                      | Row                                                                                                      |
| -------------------------- | -------------------------------------------------------------------------------------------------------- |
| `quality_check_runs`       | run 42: same subject, source `unresolved-fonts`                                                          |
| `quality_check_run_inputs` | run 42 read: variant `main`, 7; theme `brand`, 2; token `installation-fonts` = application version 2.0.3 |
| `quality_findings`         | rule `unresolved-font`, node `t`, run 42                                                                 |

Installing the font or upgrading the application changes the token, so the run is outdated and
re-runs.

**3. Readability (remote, a future checker): "this paragraph reads at a high level".**

| Table                      | Row                                                                 |
| -------------------------- | ------------------------------------------------------------------- |
| `quality_check_runs`       | run 43: source `readability`, state completed (ADR 0012), ran 09:40 |
| `quality_check_run_inputs` | run 43 read: variant `main`, 6                                      |
| `quality_findings`         | rule `reading-level`, run 43                                        |

The author edits the variant (counter 7): the finding shows as outdated, "the variant changed since
this was checked", until the checker's next run replaces it.

**4. A colleague's note (manual): "use the formal salutation here".**

| Table                      | Row                                           |
| -------------------------- | --------------------------------------------- |
| `quality_findings`         | source `manual`, the note, its author, no run |
| `quality_finding_comments` | replies on the note                           |

It has no run and no stamp, is never outdated, and stays until someone resolves it.

**Ignoring** any of them adds a row to `quality_finding_ignores` for (subject, source, rule,
fingerprint), as today; it holds while the finding has the same fingerprint, across re-runs.

## Consequences

- No stored finding is shown as current when its inputs changed: the stamp is checked on every read,
  whatever triggered or failed to trigger a re-check.
- Re-checks are precise: the stamps say which runs read a resource, so no change path has to know
  which subjects it affects.
- Adding a local check is a pure function plus its declared requirements; the runner stamps what it
  provides. An input that is not a counted resource needs a token, added once where it is introduced.
- Writes pay in the background (about 18 ms per variant per re-check measured, most of it storing);
  a popular stencil saved often re-checks its users once per burst, not per save.
- The stamp tables grow with runs (about ten rows per run, replaced on each re-run), not with history.

### Migration

The current quality data is deleted: every finding, comment and ignore. No one uses the quality
checks yet; quality is alpha, so the change ships in a minor release and the changelog says so.

### Build order

1. `quality_check_runs` and `quality_check_run_inputs`; the runner records the stamp; findings point
   at their run; the migration deleting current data.
2. Current/outdated on every read (panel, report, usage page), and re-check after commit through the
   stamps, coalesced.
3. The fit check as the stencil-fit source; `StencilParameterQualitySource` removed.
4. Tokens for fonts, images and installation-wide state.
5. [`docs/quality.md`](../quality.md) rewritten to describe what is built.

Each step lands with a test that fails before it, the stale case included: save a stencil without
touching the template, and the template's finding about it is marked outdated and re-checked.

## Open questions

- Whether a local source should be re-run synchronously when the panel opens on an outdated run,
  instead of showing it outdated for the moment until the background re-check lands.
- The token for installation-wide state: the application version is coarse (any upgrade re-checks
  everything); a hash of the installed fonts would be finer.

## Related

- [`docs/quality.md`](../quality.md): the ledger as built.
- [ADR 0011](0011-quality-check-input-model.md): declared data requirements, through which the runner
  provides, and so stamps, what a check reads.
- [ADR 0012](0012-check-run-lifecycle.md): the run lifecycle; its single-flight and coalescing apply
  to re-checks.
- [ADR 0026](0026-revisions-releases-and-the-working-copy.md): content-addressed revisions, a
  different thing from the change counter used here.
