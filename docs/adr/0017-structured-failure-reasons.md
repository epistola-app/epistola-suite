# ADR 0017: Structured failure reasons for asynchronous work

- Status: Proposed
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

`last_error` becomes a typed JSON record. Failures are stored as data; the
sentence is composed when it is rendered.

```json
{
  "code": "EXCHANGE_APPLICATION_UNKNOWN",
  "params": { "namespace": "public-services" },
  "detail": "401 Unauthorized: {\"error\":\"invalid_client\"}"
}
```

- **`code`** is a closed Kotlin vocabulary. It decides the title and the guidance,
  the way `ExchangeConnectionStatus.guidance` and `KnownFeatures.metadata`
  already decide theirs: one definition, every surface.
- **`params`** are the named values a message needs to be specific — the
  namespace that was withdrawn, the version that cannot follow, how long a
  submission has gone undecided.
- **`detail`** is what the far side or the transport actually said. Kept, shown
  as supporting detail, logged in full — never the headline.

Typed, not open. The JSON is a Kotlin data class with a code enum, bound through
the existing `jdbi3-jackson3` JSONB support. The flexibility being bought is
_that different codes carry different parameters_, not that anything at all may
be written.

A remote decision is carried, not flattened: Exchange's `errorCode` and
`errorDetail` become `params` and `detail` under a Suite code, so the remote
vocabulary survives as data.

## Why JSON rather than a code and a detail column

Two columns — `error_code`, `error_detail` — is the obvious simpler option, it
needs no JSON handling, the code is trivially indexable, and it is exactly what
Exchange already does on `publication_submission`. It was seriously considered.

It fails on the cases that motivated this. Several of these messages are
specific, and their specifics come from the failure, not the code:

- the namespace an organization withdrew,
- the namespace a catalog is bound to but no longer granted,
- how long Exchange has held a submission without deciding,
- the cause of an unreachable host.

With a code and a free-text detail, those sentences have to be composed at
write time again and stored in `detail` — which is precisely the defect, only
now with a code beside it. Parameters are what make render-time composition
possible for _every_ message rather than only the parameterless ones. That is
the whole decision; everything else about the two shapes is close to a wash.

Indexing is not a real difference at this size: `(last_error ->> 'code')` takes
an expression index if a query ever needs one, and neither table is large.

## Consequences

- Wording changes need no migration, and improve rows that already exist.
- The write path records what happened; it no longer decides what to say.
- One vocabulary can serve the settings page, the catalog page, logs, metrics
  and — later — REST or MCP, without each re-deriving prose from a string.
- Codes become alertable and countable, which free text never was.
- More machinery than a string: a record type, a mapper, and a code-to-copy
  table that must be kept complete. A missing code must render as the raw detail
  rather than an empty box, and a test should hold the enum and the copy together
  the way `ExchangeStatusBadgeTest` holds statuses and badges.
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
