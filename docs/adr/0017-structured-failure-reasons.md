# ADR 0017: Structured failure reasons for asynchronous work

- Status: Accepted
- Date: 2026-08-31

## Context

Exchange publication records why work is not progressing in `last_error`, on
`exchange_tenant_connections` and on `catalog_release_publications`. It is free
text, composed at the moment of failure — inside a background worker — and
rendered verbatim as the explanation a person reads.

That has failed in four distinct ways, all observed:

- **The transport's words became the explanation.** The Exchange page showed
  `401 Unauthorized: {"error":"invalid_client"}`, because a worker stored
  `exception.message`. It names a symptom to nobody who can act on it.
- **Improving the wording did not improve anything already recorded.** Rows keep
  the sentence written when they failed, so a copy fix only reaches failures that
  have not happened yet. The row a person is actually looking at is the one it
  cannot help.
- **Structure gets flattened and then re-split by eye.** Publications store
  `"CATALOG_PUBLICATION_REJECTED: Tenant connection is not authoritative for
this catalog"` — a remote code and a remote detail, concatenated into prose,
  after which neither can be read back reliably.
- **Presentation leaked into the write path.** Deciding what a person should be
  told is now the job of a `SKIP LOCKED` poller, which is the wrong place to make
  that decision and an awkward place to change it.

The underlying mistake is uniform: **prose is composed where the failure happens
rather than where it is read.**

## Decision

`last_error` is replaced by two columns, `error_code` and `error_detail`.
Failures are recorded as data; the sentence is composed where it is read.

- **`error_code`** is a closed Kotlin vocabulary. It decides the title and the
  guidance, the way `ExchangeConnectionStatus.guidance` and
  `KnownFeatures.metadata` already decide theirs: one definition, every surface.
- **`error_detail`** is what the far side or the transport actually said. Kept,
  shown as supporting detail, logged in full — never the headline.

This is the shape Exchange already uses on `publication_submission`, so both
sides of the protocol describe a failure the same way, and a remote decision
crosses without being flattened: Exchange's `errorCode` maps to a Suite code and
its `errorDetail` is carried as detail.

A message that needs to be specific takes its specifics from the row it is
rendered beside, not from the error record.

## Why not JSON with parameters

The tempting shape is `{ code, params, detail }`, on the argument that several
of these messages are specific — the namespace an organization withdrew, the
namespace a catalog is bound to but no longer granted, how long a submission has
gone undecided — and that a code with only free text forces those sentences to
be composed at write time again.

That argument does not survive looking at the rows. `catalog_release_publications`
already stores `namespace`, `version`, `attempts`, `submitted_at` and
`created_at`; `exchange_tenant_connections` already stores the organization, the
granted namespaces and the status. **Every value those messages interpolate is
already a structured column on the row being rendered**, and the rest are
configuration the renderer holds anyway, like `submitted-timeout`. A `params`
object would restate, in a JSON blob, facts the row states properly one column
to the left — and two copies of the same fact is exactly how they come to
disagree.

There is one case parameters would genuinely serve: an error describes the state
**at the time it happened**, while the row describes the state **now**. A
publication refused for a namespace that has since been re-pointed would render
against the new namespace and say something untrue. It is a real gap, and it is
small: a stale reason on a row that has moved on should be cleared rather than
carefully rendered, and `error_detail` can carry the historical specifics as
evidence. If a case appears where that is not enough, a `params` column can be
added then, against a real example rather than an imagined one.

## Consequences

- Wording changes need no migration, and improve rows that already exist.
- The write path records what happened; it no longer decides what to say.
- One vocabulary can serve the settings page, the catalog page, logs, metrics
  and — later — REST or MCP, without each re-deriving prose from a string.
- Codes become alertable, countable and indexable, which free text never was.
- The two products describe failures in the same shape, so mapping between them
  is a lookup rather than a parse.
- A code-to-copy table has to be kept complete. A missing code must render as
  the raw detail rather than an empty box, and a test should hold the enum and
  the copy together the way `ExchangeStatusBadgeTest` holds statuses and badges.
- Messages are rendered against current row state, so a reason left behind by a
  row that has since changed can mislead. Reasons are cleared when the thing
  they describe is resolved.
- Both columns live in unreleased migrations on this branch, so they are edited
  in place and no data is preserved.

## Relationship to `ValidationCode`

`ValidationCode` is the vocabulary for **synchronous command rejection** — a
caller asked for something and was refused, and the code travels out through
`ValidationException` toward a ProblemDetail. This is the vocabulary for
**asynchronous work that has not finished**, read later by someone who did not
make the call.

They are deliberately separate. The sets barely overlap in practice, and merging
them would tie an error model that is becoming an HTTP contract to one that is a
UI concern. A single code may legitimately appear in both, spelled the same way;
that is a coincidence worth allowing and not a reason to share an enum.

## Alternatives considered

### JSON with a parameter object

Rejected above, on the evidence that the parameters already exist as columns on
the rows being rendered. It buys flexibility that is paid for immediately in
duplicated facts, and its one genuine advantage — describing a past state — is
better served by clearing reasons that no longer apply.

### Keep free text, prefix a code

Rejected. This is the status quo — `"CODE: detail"` is already what publication
failures store — and it produces exactly the flattening this decision exists to
undo.

### Store only a code, drop the detail

Rejected. The remote's own wording is the first thing an operator wants when a
publication is refused for a reason the code cannot fully express. It is bad as
a headline and valuable as evidence.

### Fix the wording at each write site and leave the shape alone

Rejected, having tried it. It improves what future failures store and leaves
every existing row untouched, so the page a person is looking at right now still
reads badly. It also leaves presentation in the worker.
