# ADR 0056: Where an interactive generation request keeps its form

- **Status:** Accepted — implemented (alpha, with interactive generation)
- **Date:** 2026-11-21
- **Deciders:** Epistola team
- **Tags:** database, generation, partitioning, retention, personal-data
- **Related:** contract ADR 0011 (interactive generation), [ADR 0055](0055-interactive-generation-form-without-sign-in.md)
  (the form without sign-in), [`docs/interactive-generation.md`](../interactive-generation.md),
  [`docs/interactive-generation-load-impact.md`](../interactive-generation-load-impact.md)

## Context

A generation request created with `interactive` carries a form: the trigger, when the wait ends,
the caller's texts (title, instructions, links), the return URL, the locale, what the analysis found
missing, the data exactly as the caller sent it, what the person changed, and who asked. The request's
own `data` becomes what the person submits.

Two things are coming that decide where that state lives:

- **Saving along the way.** The form will save what the person has filled in while they work, so they
  can leave and come back. Today a form is written twice, at acceptance and at submit; with saving it is
  written many times while the request waits.
- **Form designs.** Authors will design the form (layout, order, steps) rather than it being generated
  from the data contract. The design belongs to the template; each request keeps more state of its own
  against it (progress, the step reached, what was saved).

`document_generation_requests` is the pipeline's hot table: the job poller claims rows from it
(`FOR UPDATE SKIP LOCKED`), updates their status, and stale-job recovery scans it. It is partitioned
monthly on `created_at` and dropped by retention.

Reviewing the migration before it shipped showed what keeping a form apart from its request costs.

- **Row deletes are not partition drops.** Requests are deleted by the cascades from their tenant,
  template, variant, version and environment. Their forms stayed behind, with the caller's data and
  texts — personal data with no owner left. Shared retention alone does not cover this.
- **A foreign key cannot carry the delete.** Retention drops an old request partition with
  `DROP TABLE … CASCADE`. On PostgreSQL 17 and 18 that drops a foreign key between two partitioned
  tables _whole_, with only a NOTICE: after the first retention run nothing would be enforced, and
  nothing would say so. (Checked on both versions with the drop exactly as
  `epistola_drop_partitions_before` runs it.)

## Options

### A. A separate table, partitioned like the requests, kept in step by a trigger

- Writes to the form — every save while the person works, and whatever form designs add — land in a
  table of its own, not on the rows the poller claims and updates.
- Requests without a form carry nothing of it.
- Three mechanisms keep the two tables in step: shared retention (same partition key, same months, same
  retention), `has_form` on the request, and a delete trigger.
- The job status, listing, result emission and the expiry sweep read the form in a second query or a
  join, only for requests with `has_form` (measured: hundredths of a millisecond per call,
  `docs/interactive-generation-load-impact.md`).

### B. The form's columns on `document_generation_requests`

- One row, one lifecycle: deletes and retention need no trigger or flag, and no read needs a second
  query.
- Every save while the person works becomes an update of a hot row: a new row version each time, bloat
  and vacuum work on the table the poller works on. Every piece of state form designs add becomes a
  column of the pipeline's most important table, which then mixes two concerns.

### C. A separate table with a foreign key to the requests

- The first retention run drops the foreign key without saying so (see Context).

## Decision

**A.** The form lives in `generation_request_interactions`, beside its request:

- partitioned on the request's `created_at`, so it falls in the request's month, and given the
  requests' retention, so retention drops both in the same run (`CorePartitionedTables`);
- found from the request through `has_form`, so the many requests without a form never look it up;
- deleted with its request by `trg_requests_delete_interaction` (`AFTER DELETE … WHEN (OLD.has_form)`),
  which survives retention drops and is cloned onto partitions created later (checked on PostgreSQL 17
  and 18).

B is rejected because saving along the way and form designs would put a form's frequent writes and
growing state on the hot table. Option A's cost — keeping two tables in step — is paid once, in the
schema, and tested; B's would be paid on every save, in the pipeline. C is rejected because retention
silently removes the foreign key.

## Consequences

- The trigger is load-bearing: it is what keeps personal data from outliving its request. It is
  covered by `InteractiveGenerationIntegrationTest` (deleting the tenant leaves no form). A new way to
  remove requests that fires no row trigger (`TRUNCATE`, dropping a partition outside retention) must
  remove the forms itself.
- Saving along the way writes to `generation_request_interactions` only; it must not touch the request
  row until submit, or it brings the churn back to the hot table.
- Per-request state that form designs add belongs in `generation_request_interactions` (or a table
  beside it), never on `document_generation_requests`. The design itself belongs to the template.
- A form's month is its request's: rows must be written with the request's `created_at`, never their
  own time, or they land in another partition and retention drops them at another time.
- `generation_form_link_redemptions` (redeemed link nonces) stays a table of its own: it belongs to no
  request and lives minutes.

## Open question, separate from this decision

`generation_results` deliberately has no foreign key to the tenant: results survive tenant deletion
so collectors can still read them. Interactive generation copies the submitted `data` and
`editedFields` into the results, so a deleted tenant's form data stays until results retention (one
month by default). Proposed: `DeleteTenant` clears those two columns on the tenant's results.
