# ADR 0056: Where an interactive generation request keeps its form

- **Status:** Proposed (2026-11-21)
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

The first build, unreleased, keeps the form in a table of its own, `generation_request_interactions`,
partitioned like `document_generation_requests` (monthly, on the request's `created_at`) and given
the same retention, so old months of both are dropped in the same maintenance run. Requests record
`has_form`, so the many without one skip the form table: looked up by request id alone, it probes
every partition.

Reviewing the migration before it ships showed what keeping two tables in step costs.

- **Row deletes are not partition drops.** Requests are deleted by the cascades from their tenant,
  template, variant, version and environment. Their forms stayed behind, with the caller's data and
  texts — personal data with no owner left. Retention alone does not cover this.
- **A foreign key cannot carry the delete.** Retention drops an old request partition with
  `DROP TABLE … CASCADE`. On PostgreSQL 17 and 18 that drops a foreign key between two partitioned
  tables _whole_, with only a NOTICE: after the first retention run nothing would be enforced, and
  nothing would say so. (Checked on both versions with the drop exactly as
  `epistola_drop_partitions_before` runs it.)
- **So the first build now uses a trigger.** `AFTER DELETE` on the requests, `WHEN (OLD.has_form)`,
  deletes the form. On both versions it survives retention drops and reaches partitions created later.
  It works, but it is a second mechanism to keep the two tables in step, beside the shared retention
  and the `has_form` flag.

Every read of a job with a form also pays for the second table: the job status and listing attach
forms in a second query (`InteractiveStates`), result emission joins the form for `data` and
`editedFields`, and the expiry sweep starts from the requests and joins forms.

The migration has never been released, so it can still be edited in place. Once it ships, changing
where the form lives means a data-moving migration on two of the largest tables.

## Options

### A. A separate, identically partitioned table, kept in step by a trigger (as built)

- Requests without a form carry nothing of it.
- Three mechanisms keep the tables in step: shared retention, the `has_form` flag, the delete trigger.
  Each was needed because the one before it did not cover a case.
- A second query or join on the job status, listing, result emission and the expiry sweep.
- Separating the personal data protects nothing: the request's own `data` is already personal data in
  `document_generation_requests`.

### B. The form's columns on `document_generation_requests`

About ten nullable columns: `interactive_trigger`, `interactive_expires_at`, `return_url`, `title`,
`instructions`, `links`, `locale`, `missing_fields`, `caller_data`, `edited_fields`, `requested_by`.

- Deletes and retention need nothing: the form is the request's row.
- No trigger, no `has_form` (a non-null `interactive_trigger` says the request has a form), no second
  query, no join.
- A null column costs a bit in the row's null bitmap; JSON values over about 2 kB are stored out of
  line (TOAST), and a status update copies only a pointer to them. Requests without a form barely
  change.
- The requests table is the poller's hot table: it claims rows (`FOR UPDATE SKIP LOCKED`) and updates
  their status. Rows with a form get wider; small inline JSON is copied on each status update.

### C. A separate table with a foreign key to the requests

Rejected: the first retention run drops the foreign key without saying so (see Context).

## Decision (proposed)

**B.** The form lives in columns of the request it belongs to.

The decisive reason is that one row has one lifecycle. Every problem found in review — orphaned forms,
the vanishing foreign key, the trigger to replace it — exists only because the form has a row of its
own that must be kept in step with the request's. Option B removes that class of problem rather than
adding a mechanism per case, and removes a query or join from each hot path of the pipeline.

To be confirmed before acceptance: the claim and status-update path is not measurably slower with
form rows present. The load measurement of `docs/interactive-generation-load-impact.md` is repeated
on the merged schema, against the same baseline.

## Consequences

- The unreleased migration `V20261119170913__core_interactive_generation.sql` is rewritten: the form
  table, its index, the trigger and `has_form` go; the columns join `document_generation_requests`.
  `generation_form_link_redemptions` (redeemed link nonces) stays a table of its own: it belongs to no
  request row and lives minutes.
- `InteractiveStates`, `InteractiveForms`, `GenerateDocument`, `GetInteractiveForm`,
  `SubmitInteractiveForm`, `CancelInteractiveForm`, `ExpireInteractiveForms`, `PreviewInteractiveForm`,
  `EmitGenerationResult` and the job queries read and write the request's own columns. The migration
  chain snapshot (`migration-chain/intended.txt`) and the load-impact document follow.
- The expiry sweep finds waiting requests through the existing status index, as it does now.
- If the measurement shows the claim path slower, this ADR is revisited before the migration ships,
  not after.

## Open question, separate from this decision

`generation_results` deliberately has no foreign key to the tenant: results survive tenant deletion
so collectors can still read them. Interactive generation copies the submitted `data` and
`editedFields` into the results, so a deleted tenant's form data stays until results retention (one
month by default). Proposed: `DeleteTenant` clears those two columns on the tenant's results. This
needs no schema change, and holds whichever option is chosen above.
