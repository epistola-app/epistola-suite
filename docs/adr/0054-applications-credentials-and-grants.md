# ADR 0054: Applications, credentials and grants

- **Status:** Proposed (2026-11-09; step 1 being built, fixes [#749](https://github.com/epistola-app/epistola-suite/issues/749))
- **Date:** 2026-11-09
- **Deciders:** Epistola team
- **Tags:** security, authorization, api-keys, oauth, mcp, generation, collect
- **Related:** [ADR 0048](0048-authorization-decisions-behind-one-decision-point.md) (one decision
  point), [`docs/auth.md`](../auth.md), [`docs/authorization.md`](../authorization.md)

## Context

A tenant has several calling applications: a case system, a civil-registry application, an MCP
client. Each must receive only the documents it asked for. Today an API key is three things at
once:

- **the identity** — who is calling (the key's id becomes the principal's user id);
- **the credential** — how the caller proves it (the secret);
- **the grant** — what the caller may do (`api_keys.roles`).

Nothing records who submitted a generation request. `FetchGenerationResults` filters on the tenant
alone, so every consumer of a tenant drains every result ([#749](https://github.com/epistola-app/epistola-suite/issues/749)),
and fetching, downloading or deleting a document by id is tenant-wide too. `consumer_id` spreads
partitions over the nodes of one consumer; it was never an access boundary.

Tying ownership to the key does not work: rotating a key would change who owns the documents.
Taking it from a request field does not either: another key of the same tenant could name it.

Two more needs are coming, and the model must have room for them without being rebuilt:

- **A person authorizing a client** (OAuth authorization code, for MCP first).
- **A system authorizing itself without a person** (OAuth client credentials, for an integration
  such as Gzac), either with a token Epistola issues or one from an identity provider the
  installation already trusts.

## Decision

### 1. An application is the identity; credentials belong to it

An **application** is a calling system in one tenant. It has an id (UUIDv7, never reused), a name,
a description, the tenant roles it acts with, and whether it sees every document of the tenant.
Applications are per tenant: the same system calling two tenants is two applications.

A **credential** proves that a caller is an application. Step 1 has one kind, the API key; an
application holds any number of them, so a key is rotated by adding the new one and revoking the
old, and ownership does not move. The OAuth client of step 2 is another kind of credential on the
same application.

**The roles live on the application.** A key created for an application carries a copy of the
application's roles (`api_keys.roles`), so a rollback to a release that reads roles from the key
still grants exactly what it granted before. Roles are set when the application is created and are
not edited in step 1, as a key's are not today; editing them comes with step 2 and must then update
the copies.

### 2. Every generation request records its application

The application comes from the authenticated credential, never from the request body, so no
contract field is needed. It is stamped on the generation request when it is accepted and copied to
the document and to the generation result when they are written.

A caller **restricted to its own documents** sees only rows stamped with its application: the
collect feed, job listing and status, cancelling, document listing, download and delete. Anything
else is not found, exactly as a document of another tenant is.

These callers **see every document of the tenant**, as everyone does today:

- an application marked as seeing every document;
- a person signed in to the UI (the UI shows counts and recent jobs, not document content);
- a bearer token or the demo shared secret, which have no application.

Rows without an application — written before this change, or by a caller without one — are seen only
by the callers in that list.

The predicate is bound into the SQL of each query from the principal (`ADR 0048`, section 2: lists
are filtered in the database), not applied to the rows afterwards.

### 3. Existing keys keep working unchanged

The migration turns **every key that is not revoked into an application of its own**, named after
the key, with the key's roles, marked as seeing every document. Two keys in one tenant were two
integrations as far as the data shows, and merging them would widen one key's roles to the other's.
An administrator then narrows each application by switching off "sees every document", adds keys to
it and revokes old ones.

A key with no application — a revoked one, or one inserted by an older release after a rollback —
authenticates, if it still can, as before: its own roles, every document of the tenant.

### 4. Room for resource limits, not built yet

Limiting an application to catalogs, environments or templates is an ADR 0048 policy question
about a subject, not a new mechanism. The principal carries the application, so the decision point's
`Subject` can hold it (kind `APPLICATION`) and its `scope()` can answer `Only(...)` for it once
resource attributes exist (ADR 0048, delivery step 3). No grant table is added until then.

### 5. Steps 2 and 3 (direction, decided when built)

- **Machine OAuth (step 2).** Client credentials as a credential kind on an application: Epistola
  issues the token (a client id and `private_key_jwt` or a secret), or the installation trusts an
  external issuer and maps its client id (`azp`) to an application. Both arrive at the same
  principal; supporting both is cheap once one exists.
- **Delegated OAuth (step 3).** A person grants a client access. That is a **grant** (user, client,
  scopes), not an application: an MCP client belongs to the installation, not to a tenant. The
  effective rights are the person's roles narrowed by the consented scopes, which are named after
  the tenant roles. Epistola is the authorization server (Spring Authorization Server is already a
  dependency), federating login to the installation's identity provider, so tenants and roles stay
  in Epistola's tokens. Whether a grant covers every tenant the person belongs to or one chosen at
  consent is open: the REST API carries the tenant in its paths and can serve a token for several,
  while MCP tools take the tenant from the credential and would need a tenant argument.

## Options considered

- **Ownership per API key.** Rejected: rotation moves ownership, and a consumer that rotates loses
  its documents.
- **An application field on the request.** Rejected: any key of the tenant could name another
  application.
- **Ownership per environment.** Rejected: an environment decides which release renders, not who
  asked.
- **One default application per tenant for all existing keys.** Rejected: keys with different roles
  cannot share one set without widening one of them.
- **Chosen:** an application entity that owns credentials and documents, existing keys each becoming
  one that sees every document.

## Consequences

- **Additive for integrations.** No contract change; existing keys see what they saw. Isolation
  starts when an administrator creates an application, or narrows a migrated one.
- **Three tables gain a nullable column** (`document_generation_requests`, `documents`,
  `generation_results`), partitioned and high volume; each restricted query gains a predicate.
- **Collect cursors stay per key.** `consumer_id` is still the key's id, so a rotated key starts from
  the oldest result its application still holds. Moving cursors to the application is a follow-up,
  and must keep two keys of one tenant-wide application apart.
- **Deleting an application is possible once its keys are revoked.** Its documents stay, seen only by
  tenant-wide callers; the id is never reused, so no later application inherits them.
- **Credentials are managed in the UI only**, as API keys are today; neither REST nor MCP manages
  them.

## Delivery

1. **Applications and document ownership** — this ADR's sections 1–3, UI only. Fixes #749.
2. **Machine OAuth** — section 5.
3. **Delegated OAuth for MCP** — section 5.
