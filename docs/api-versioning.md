# REST API versioning

> How the external REST API (`/api/...`) is versioned, and why. This settles the
> URL-prefix-vs-media-type question that issue #27 raised (and which the codebase
> answered by **not** adopting a `/api/v1/` URL prefix). It covers only the
> **external REST API**; the UI handlers and the catalog wire format version
> independently (see [below](#relationship-to-other-version-axes)).

## The decision

**Epistola versions its REST API by media type, not by URL path.**

- **URLs are stable and unversioned.** Resources live at `/api/...` with no
  version segment — e.g. `/api/tenants`,
  `/api/tenants/{tenantId}/catalogs/{catalogId}/templates`. There is no
  `/api/v1/` (the proposal in #27, deliberately not adopted).
- **The version travels in the media type.** Clients send and accept
  `application/vnd.epistola.v1+json`; the `v1` token is the API version. Streamed
  endpoints use `application/vnd.epistola.v1+ndjson` (newline-delimited JSON).

## The convention

Every REST request carries the versioned media type on **both** the request body
content type and the accept header:

```
Content-Type: application/vnd.epistola.v1+json
Accept:       application/vnd.epistola.v1+json
X-API-Key:    <tenant-scoped key>
```

Content negotiation is strict — a mismatch is an RFC 9457 problem response, not a
silent fallback:

- An unsupported request `Content-Type` → **415 Unsupported Media Type**
  (`…/errors/unsupported-media-type`, with `contentType` + `supportedTypes`).
- An `Accept` the API can't satisfy → **406 Not Acceptable**
  (`…/errors/not-acceptable`, with `acceptHeader` + `supportedTypes`).

### Example

```bash
curl -X POST https://example.epistola.app/api/tenants/acme/catalogs/default/templates \
  -H 'Content-Type: application/vnd.epistola.v1+json' \
  -H 'Accept: application/vnd.epistola.v1+json' \
  -H 'X-API-Key: <key>' \
  -d '{ "id": "invoice", "name": "Invoice" }'
```

A streaming collect endpoint negotiates the NDJSON variant instead:

```
Accept: application/vnd.epistola.v1+ndjson
```

## Why media-type versioning (not a URL prefix)

Issue #27 proposed `/api/v1/...`. We chose media-type (content-negotiation)
versioning instead because:

- **Resource identity stays stable across versions.** A template's URL is the
  same whether a client asks for `v1` or a future `v2` representation — the URL
  identifies the _resource_, the media type selects its _representation_. URL-path
  versioning conflates the two and duplicates every path on each bump.
- **A version bump is additive, not a parallel tree.** Introducing `v2` means
  serving a second media type from the same endpoints during a deprecation
  window, rather than standing up and routing a whole `/api/v2/` path space.
- **It composes with the existing problem-details + API-key model.** Negotiation
  failures already surface as typed RFC 9457 problems (415/406), so a wrong or
  missing version degrades to a clear, machine-readable error.

## Introducing a future version

When a breaking change to the REST representation is needed:

1. Add the new representation under `application/vnd.epistola.v2+json` (and
   `+ndjson` where streamed). Keep `v1` served from the same URLs during the
   deprecation window so existing clients keep working.
2. Announce the deprecation window; once it closes, `v1` requests get a
   `406`/`415` against the now-unsupported media type.
3. URLs do not change.

## Where the contract lives

The REST surface is defined by an **OpenAPI document in the external
`epistola-contract` repository**, not in this repo. The controllers here
implement server interfaces generated from that contract and shipped as the
`app.epistola.contract:server-kotlin-springboot4` artifact (see
`epistola-contract` in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)).
The OpenAPI document encodes the versioned media type as the `produces`/`consumes`
content type on its operations — so the header convention above is the spec's
convention. A note describing it belongs in that contract's OpenAPI `info`/docs;
this repo only consumes the generated jar.

## Relationship to other version axes

This media-type version is **only** the external REST API's. It is independent of:

- **UI handlers** (`/tenants/...`, `/themes/...`) — internal, server-rendered,
  unversioned, free to change (see [`CLAUDE.md`](../CLAUDE.md) → Backend
  Architecture). UI code must never call the REST API.
- **The catalog exchange wire format** — its own catalog-wide `schemaVersion`
  with a migration chain (see [`docs/exchange/`](exchange/README.md) and
  [ADR 0007](adr/0007-catalog-wire-format-migrations.md)).
- **The MCP server** (`/api/mcp`) — a separate Streamable-HTTP protocol surface,
  not part of the REST representation versioning (see [`docs/mcp.md`](mcp.md)).

## History

- Supersedes **#27** ("Implement versioned REST API `/api/v1/`"), which is closed:
  the suite versions via the media type instead of a URL prefix.
