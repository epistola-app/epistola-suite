# Support service protocol (v1, requests v2)

> **Status:** Alpha

The wire protocol between an Epistola Suite installation and the Epistola support service: how an
installation registers (applies for support), how Epistola activates or declines it, how it gets
tokens, and the three services it can use — telemetry, support requests, the catalog mirror and usage. [ADR 0050](adr/0050-support-connection.md)
records why it is shaped this way and [ADR 0051](adr/0051-catalog-mirror.md) covers the mirror.
The shapes of every endpoint the service defines itself are in [`support-api.yaml`](support-api.yaml),
an OpenAPI file copied from the support service, which checks its answers against it; the suite
checks its stand-in service against the same file (`SupportFakeServiceConformanceTest`). This page
explains the behaviour.

The suite always initiates. The support service never calls an installation, so an installation
needs outbound HTTPS to one origin and nothing inbound.

Every timestamp is ISO-8601 in UTC. Every JSON body is UTF-8. Unknown JSON fields are ignored by
both sides, so either side may add optional fields within v1.

## Finding the service

The suite takes the service's **issuer** URL from, in order:

1. `epistola.support.endpoint` (Helm `support.endpoint`);
2. the `support.issuer` field of the product discovery document
   (`https://{product-domain}/.well-known/epistola.json`, ADR 0030);
3. the built-in default `https://support.{product-domain}`.

It then reads `GET {issuer}/.well-known/oauth-authorization-server` (RFC 8414):

```json
{
  "issuer": "https://support.epistola.app",
  "token_endpoint": "https://support.epistola.app/oauth2/token",
  "registration_endpoint": "https://support.epistola.app/oauth2/register",
  "jwks_uri": "https://support.epistola.app/oauth2/jwks",
  "grant_types_supported": ["client_credentials"],
  "token_endpoint_auth_methods_supported": ["private_key_jwt"],
  "token_endpoint_auth_signing_alg_values_supported": ["ES256"],
  "scopes_supported": ["telemetry", "requests", "mirror", "usage"],
  "epistola_api_endpoint": "https://support.epistola.app/api/v1",
  "epistola_otlp_endpoint": "https://support.epistola.app/otlp",
  "epistola_requests_endpoint": "https://support.epistola.app/api/v2/requests"
}
```

`scopes_supported` lists the services the support service offers now. The suite asks only for
selected services in this list; a selected service missing from it is shown as "no longer offered by
Epistola" ([Adding, retiring and granting services](#adding-retiring-and-granting-services)). A
service that leaves it out is taken to offer whatever the suite asks for.

The suite refuses the metadata unless `issuer` equals the URL it asked, and unless every endpoint
in it is on the issuer's origin (same scheme, host and port). Plain `http:` is refused unless the
operator set `epistola.support.allow-http` (local development only); an `http:` endpoint configured
without it stops startup. The private key never leaves the installation, but its assertions and
every upload do, and they must only go to that origin.

### Configuration

| Setting                            | Default | Effect                                                                                                                                     |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `epistola.support.enabled`         | `false` | Register with the support service and use what Epistola grants. Off: nothing is sent.                                                      |
| `epistola.support.endpoint`        | blank   | The service's issuer URL; blank: found as above. Helm `support.endpoint` (https only).                                                     |
| `epistola.support.allow-http`      | `false` | Permit a plaintext `http:` service. Local development only; not exposed by the Helm chart.                                                 |
| `epistola.support.auto-register`   | `false` | Let the check register on its own, and again at once after an expiry. Helm `support.autoRegister`. Off: an administrator presses Register. |
| `epistola.support.services`        | empty   | The services this installation uses until an administrator saves a selection (below). Helm `support.services`.                             |
| `epistola.support.check-interval`  | `5m`    | How often one node refreshes the connection status (and registers, with auto-register).                                                    |
| `epistola.support.connect-timeout` | `5s`    | Connect timeout for every call to the support service.                                                                                     |
| `epistola.support.read-timeout`    | `30s`   | Deadline for one whole call, request and answer; a request body adds a second per 512 KiB. See [Reliability](#reliability).                |

**Which services the installation uses.** A service is used only when it is **selected** by the
installation **and granted** by Epistola. An installation administrator selects services under
Platform → Support connections; the selection is stored installation-wide and needs no connection to
save. Until an administrator saves one, `epistola.support.services` is the selection; from then on
the saved selection wins and the setting is ignored. Nothing is selected by default. A name the
suite does not know (a retired or a newer service) is kept and logged, never an error.

The services have their own settings: telemetry in `epistola.support.telemetry.*`, support requests in
[support-requests.md](support-requests.md), the catalog mirror in [catalog-mirror.md](catalog-mirror.md).

## Registration

`POST {registration_endpoint}` — RFC 7591, no authentication. The service rate-limits it per
source address.

**When the suite registers.** Only when an installation administrator presses Register under
Platform → Support connections, unless `epistola.support.auto-register` (Helm `support.autoRegister`,
off by default) lets the status check register on its own. Before that the suite does not contact the
support service at all.

```json
{
  "client_name": "Gemeente Voorbeeld — production",
  "grant_types": ["client_credentials"],
  "token_endpoint_auth_method": "private_key_jwt",
  "jwks": {
    "keys": [
      { "kty": "EC", "crv": "P-256", "x": "…", "y": "…", "kid": "…", "use": "sig", "alg": "ES256" }
    ]
  },
  "software_id": "epistola-suite",
  "software_version": "2.0.0",
  "epistola_installation_id": "01928f6e-…",
  "epistola_environment": "production",
  "epistola_company_name": "Gemeente Voorbeeld",
  "epistola_admin_email": "beheer@voorbeeld.nl",
  "epistola_node_id": "epistola-7d9c-abcde"
}
```

- `jwks` holds exactly one public EC P-256 key. A key with private parts (`d`) is refused. `kid`
  is required; the suite uses the key's RFC 7638 thumbprint.
- `epistola_installation_id` is the installation's UUID from `app_metadata`.
- `client_name` is at most 200 characters; the `epistola_*` strings at most 200 each.

**Response** — `201 Created` for a new registration, `200 OK` when this installation id already
registered this same key (same thumbprint):

```json
{
  "client_id": "sc_01jbx4…",
  "client_id_issued_at": 1730800000,
  "client_name": "Gemeente Voorbeeld — production",
  "grant_types": ["client_credentials"],
  "token_endpoint_auth_method": "private_key_jwt",
  "jwks": { "keys": [{ "…": "…" }] },
  "epistola_claim_code": "K7QF-9MZ2",
  "epistola_claim_code_expires_at": "2026-11-12T14:02:11Z",
  "epistola_status": "PENDING"
}
```

- The **claim code** is eight characters from `23456789ABCDEFGHJKMNPQRSTVWXYZ`, shown as `XXXX-XXXX`.
  The service stores only its SHA-256. Registering the same key again issues a new code and the old
  one stops working, so the suite always holds the current one.
- `epistola_claim_code_expires_at` (RFC 3339, UTC, whole seconds) is when the claim code expires; it
  is `null` unless `epistola_status` is `PENDING`. A suite also accepts it absent (an older service).
  See [Expiry of unlinked registrations](#expiry-of-unlinked-registrations).
- The same installation id with a **different** key creates a separate PENDING registration; it
  never replaces an existing one. A restored copy of a production database that generates a new key
  therefore shows up as a second registration, for staff to decide on.
- Errors use RFC 7591 §3.2.2: `400 {"error": "invalid_client_metadata", "error_description": "…"}`,
  and `429` when rate-limited.

## Activating (Epistola side)

Registering is applying for support. Until Epistola activates a registration it stays `PENDING`:
it can get a token, but the token carries no scope, and every service endpoint refuses it. Epistola
staff find waiting installations grouped by the domain of the administrator's address, contact that
administrator, and activate the installation (granting scopes) or decline it. Which organization an
installation belongs to is recorded in Epistola's own systems; the support service knows
installations, not customers. The claim code is only a reference the administrator can mention when
contacting Epistola first; nothing needs to send it. Before registering, the suite asks the
installation administrator to check the company name and e-mail address it sends, and says Epistola
may contact that address.
Staff can revoke a registration at any time, and the installation can disconnect it itself
([Disconnecting](#disconnecting)).

| Status         | Token endpoint                | Service endpoints        |
| -------------- | ----------------------------- | ------------------------ |
| `PENDING`      | `200`, `scope: ""`            | `403 insufficient_scope` |
| `ACTIVE`       | `200`, scope = granted scopes | per scope                |
| `DECLINED`     | `200`, `scope: ""`            | `403 insufficient_scope` |
| `REVOKED`      | `401 invalid_client`          | `401`                    |
| `DISCONNECTED` | `401 invalid_client`          | `401`                    |

### Expiry of unlinked registrations

A `PENDING` registration that staff do not activate expires **7 days** after its current claim code was
issued (the service's `epistola.support.registration.pending-ttl`). From that moment the service
treats it as unknown: the token endpoint answers `401 invalid_client`, its tokens are refused with
`401`, and staff can no longer activate it. The service then deletes it (an hourly purge). Registering
the same key again before then issues a new claim code and starts the 7 days again. `ACTIVE` and
`REVOKED` registrations never expire, nor do `DECLINED` ones. The registration answer (`epistola_claim_code_expires_at`) and
the connection status (`claimCodeExpiresAt`) say when the current claim code expires.

The suite tells an expiry from a revocation by what it last saw, and by the expiry it stored.
`invalid_client` for a registration it never saw `ACTIVE`, once its claim code expired (or when it
was never told when), means the registration expired or staff removed it: the suite forgets the key
and, with `epistola.support.auto-register`, registers again at once with a new key and shows the new
claim code, saying the previous one expired; without it, it shows "The claim code expired on …" and
offers Register. Before the claim code expired, `invalid_client` for a `PENDING` registration is not
taken as expiry — the node's clock may be off (an assertion more than a minute ahead, or three behind,
is refused too): the suite keeps the key, records the error and tries again later.
`invalid_client` for a registration it saw `ACTIVE` is a refusal: the suite shows the connection as
refused, its services stop at once without asking for another token (only a check does, to notice a
restored registration), and it registers again only when an administrator asks. Only the service's
own JSON `invalid_client` counts: a bare or HTML `401` from whatever stands in front of the service is
"unavailable", never a refusal.

**A node whose clock is off.** The service refuses an assertion dated outside its skew with the same
`invalid_client`, so a node whose clock is badly off would read its own clock as a revocation, for
the whole cluster. Before the suite takes `invalid_client` as a refusal or an expiry, it compares the
answer's `Date` header with its own clock. When they are further apart than the service allows (60
seconds), it asks once more, with an assertion dated by the service's clock (`iat` = that `Date`).
Refused again, the answer stands as described above, so a revoked registration still becomes refused,
also on a node whose clock is off. Accepted, the problem is the node's clock: that node alone is
"unavailable" (its own services pause), nothing is marked refused or forgotten, and the other nodes
carry on: it is not the installation's last error. The node logs it once at WARN and stops
advertising the cluster capability `support`, which the scheduled support work requires (the
connection check, the support request sweep and reply poll, the mirror round): within a heartbeat that work
is owned by the other nodes, not skipped on this one. It stores the problem under its node id, so
Platform → Support connections shows, on every node, which node is how far ahead of or behind the
service, asking to set its clock right (NTP). The node clears that entry at its next token, once its
clock is right (its token refresh keeps trying, backing off); an entry not stored again for 15
minutes (the node went away) drops off. The suite does not use that token: a node whose clock is off
keeps failing until its clock is right. An answer without `Date` is taken as it is.

## Tokens

`POST {token_endpoint}`, `application/x-www-form-urlencoded` — the client credentials grant
(RFC 6749 §4.4) with JWT client authentication (RFC 7523 §2.2):

```
grant_type=client_credentials
client_id=sc_01jbx4…
client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer
client_assertion=<JWT>
scope=requests telemetry        (present but empty asks for none; absent asks for every granted scope)
```

The **assertion** is a JWT signed with the installation's private key: header `alg: ES256` and the
registered `kid`; claims `iss` and `sub` = the `client_id`, `aud` = the token endpoint URL (the
issuer URL is also accepted), a unique `jti` of at most 128 characters, `iat`, and `exp` at most
five minutes after `iat`. The service allows 60 seconds of clock skew and refuses a `jti` it has
seen before until that assertion would have expired.

**Response** `200`:

```json
{ "access_token": "eyJ…", "token_type": "Bearer", "expires_in": 600, "scope": "telemetry requests" }
```

`scope` is the intersection of what was asked and what was granted, space-separated, and is always
present (empty for a PENDING registration). A requested name the service does not offer is ignored,
not an error (RFC 6749 §3.3): the token simply does not carry it. An empty `scope=` asks for no scope
and gets a token that carries none; only an absent `scope` asks for every granted scope. The suite
always sends `scope`, with exactly the selected services that `scopes_supported` lists, so a token
never carries a service the installation did not select. When an installation administrator saves a
selection, the node that saved it drops its token, so nothing is sent with a token that lacks a
newly selected service; its next call or token refresh asks for the new selection. Every other node
sees the new selection within a second (see Reliability, "Across nodes"), drops its token as well,
and asks for the new selection with its next token. The service records what an installation
last asked for and shows it to staff, so they know what to grant. The access token is a JWT signed by the service
(`ES256`, keys at `jwks_uri`) with `iss`, `sub` = client id, `aud` = `epistola-support`,
`epistola_installation_id`, `scope`, `iat`, `exp` and `jti`. The suite treats it as opaque.

**Errors** (RFC 6749 §5.2): `400 invalid_request | unsupported_grant_type | invalid_scope` (only
for a malformed list: a name outside the RFC 6749 scope-token characters, more than 16 names, or a
name over 64 characters),
`401 invalid_client` (unknown client, bad signature, expired or replayed assertion, revoked
registration, a PENDING registration that expired). A suite receiving `invalid_client` for a
registration it saw `ACTIVE` shows the connection as refused and offers to register again; for one it
only saw `PENDING`, see [Expiry of unlinked registrations](#expiry-of-unlinked-registrations).

Every service endpoint below takes `Authorization: Bearer <access_token>`. A missing or invalid
token is `401` with `WWW-Authenticate: Bearer error="invalid_token"`; a valid token without the
endpoint's scope is `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"`; the
body's `error` is `service_paused` instead of `insufficient_scope` while Epistola paused that service
(see [Paused services](#paused-services)).
The service checks the scope against the registration as it is now, so a scope staff withdraw is
refused at once, before the token expires. The suite takes that `403` as "not granted": it stops
the service on that node at once and on every other node within a second, keeps its local data
(support requests stay pending, a mirror withdrawal stays pending), counts no failure against any item, and
learns the new grants at the next check. A token asked for after the refusal that carries the scope
again (staff granted it back) also ends it, on every node; a token asked for before it does not, as it
may predate the withdrawal.

A service endpoint that refuses a request answers `{"error": "…", "error_description": "…"}`.
`507 {"error": "quota_exceeded"}` means the installation is over a storage quota the service
enforces (mirror releases, request attachments and the like), and nothing changed. Unlike other
5xx answers it is not "try again later": the installation counts it against the item, as it does a
`400`.

## Connection status

`GET {api}/connection` — any valid token, including a PENDING one.

```json
{
  "clientId": "sc_01jbx4…",
  "installationId": "01928f6e-…",
  "status": "ACTIVE",
  "scopes": ["telemetry", "requests"],
  "claimCodeExpiresAt": null,
  "activatedAt": "2026-11-05T14:02:11Z",
  "declinedAt": null,
  "pause": {
    "scopes": ["requests"],
    "since": "2026-11-20T09:00:00Z",
    "until": null,
    "reason": "Planned maintenance of the support inbox"
  }
}
```

`activatedAt` is `null` unless ACTIVE. `claimCodeExpiresAt` is when a PENDING registration lapses
(RFC 3339, UTC), and `null` otherwise. `declinedAt` is set only while DECLINED: the suite shows the
installation as declined and uses nothing; an administrator can register again to apply again. A
declined registration is never taken for an expired one, so the suite does not register again by
itself. `scopes` are the granted scopes, paused ones included; `pause` is `null` unless services are
paused now.

### Paused services

Epistola can pause services of an active installation for a while — during an incident, a contract
question or abuse — without revoking anything. A paused scope is left out of new tokens, and its
endpoints answer `403` with `error: "service_paused"` (the `WWW-Authenticate` challenge stays
`insufficient_scope`, so a client that knows no pauses stops as for a withdrawn scope). `pause` in
the connection status says which services, since when, until when (`null`: until Epistola resumes)
and why, as Epistola's staff wrote it (at most 200 characters). The pause ends by itself at `until`.
The suite shows the pause on Platform → Support connections and on the tenant pages, with the reason;
it keeps the selection and every tenant's choice, keeps support requests and catalog changes waiting and
sends them once the pause ends, and drops telemetry meanwhile.

### Disconnecting

`DELETE {api}/connection` — any valid token, including a PENDING one. The installation ends its
connection: the registration becomes `DISCONNECTED` and the service answers `204`. From then on it is
refused like a revoked one (`401 invalid_client` at the token endpoint, `401` with its tokens), so a
repeat gets `401`. Unless another registration of the same installation id is `PENDING` or
`ACTIVE`, the service deletes the installation's catalog mirror at once and keeps its support requests for a
retention period (the service's `epistola.support.retention.requests-after-disconnect`, 90 days by
default) counted from its latest disconnect, then deletes it. An installation that registers again
within that time keeps its support requests, as they are keyed by installation id, and its changes
feed carries on after what it acknowledged.

The suite disconnects when an installation administrator presses **Disconnect** on Platform →
Epistola support. It sends the `DELETE`, then forgets its key, records the disconnect, and clears
every tenant's sharing choice and the installation's preset, so registering again asks every tenant
again. A disconnected installation does not register by itself, also with
`epistola.support.auto-register`; **Register** connects it again with a new key, and Epistola
activates it again. A token endpoint that refuses the registration (revoked, or never activated and
removed) leaves nothing to end at the service, and the suite disconnects locally. When the service
cannot be reached, nothing changes, and the page offers **Remove here only**: the suite forgets the
connection without telling the service, which keeps the registration and data until Epistola
removes them.

## Telemetry — scope `telemetry`

OTLP/HTTP with binary protobuf (the OpenTelemetry protocol specification, "OTLP/HTTP"):

- `POST {otlp}/v1/logs` — `ExportLogsServiceRequest`
- `POST {otlp}/v1/metrics` — `ExportMetricsServiceRequest`

`Content-Type: application/x-protobuf`; `Content-Encoding: gzip` is accepted. A request body is at
most 8 MiB after decompression. Success is `200` with an empty `Export*ServiceResponse`. `415` for
another content type, `413` when too large.

Every resource must name the installation in `epistola.installation.id`, with the id the token was
issued to. A request with a resource that names none, or another one, is refused with
`400 {"error": "installation_mismatch"}`; nothing of it is forwarded, and the registration shows the
refusal in the backoffice (ADR 0052 §4). The service then removes `epistola.installation.id`,
`epistola.support.client_id` and `epistola.customer` wherever they appear (resource, scope, log
record, data point), sets the first two on every resource from the token, and forwards the request
to Epistola's own collector, which accepts traffic from the service only.

Log records carry `trace_id` and `span_id` when the line was written inside a request or a dispatch
(ADR 0052 §3).

What a record carries depends on the installation's privacy settings (ADR 0052 §1–2). The
`epistola.tenant` attribute, and a metric's `tenant` tag, is the tenant's key or, while tenants are
confidential, its reference. Pseudonymised, a log record's body is the message as written in the
code without its values, `exception.stacktrace` holds exception types and frames without messages,
`thread.name` is left out, lines from libraries below WARN are not sent, and a metric's `template`
tag is dropped.

## Support requests — scope `requests` (v2)

All under `{requests}`, the metadata's `epistola_requests_endpoint`; JSON. A request is a list of **messages** and a **status** (suite ADR 0053): the request itself
carries its title, kind, the submitter's requested priority and the context sent once with it; its
question, replies and status reports are messages. `{tenantKey}` is the suite tenant's key, or,
while the installation keeps its tenants confidential, the tenant's reference (`t-` and 16 hex
digits, an HMAC of the key under a secret the installation never sends; ADR 0052 §1), which then
also replaces the key in `sourceUrl`, `consoleLogs` and `metadata` paths. The service treats both
alike, as an opaque key. Ids the suite sends are its own UUIDs and make every write idempotent.

v1's feedback endpoints (`{api}/feedback/…`, scope `feedback`) are gone; v2 replaced them while only
the pilot used them.

### Send a request

`PUT {requests}/{tenantKey}/{suiteRequestId}`

```json
{
  "title": "Preview does not load",
  "kind": "BUG",
  "requestedPriority": "HIGH",
  "status": "OPEN",
  "sourceUrl": "https://suite.example.org/tenants/acme/templates/letter",
  "consoleLogs": "[…]",
  "serverLogs": "[…]",
  "metadata": "{…}",
  "createdAt": "2026-11-05T13:59:00Z"
}
```

`kind` ∈ `BUG FEATURE_REQUEST QUESTION OTHER`; `requestedPriority` ∈ `LOW MEDIUM HIGH CRITICAL`, the
submitter's; `status` ∈ `OPEN IN_PROGRESS RESOLVED CLOSED`. `title` is at most 1000 characters.
`sourceUrl`, `consoleLogs`, `serverLogs` and `metadata` are optional strings (`sourceUrl` at most
4096 characters, `consoleLogs` and `serverLogs` at most 256 KiB, `metadata` at most 16 KiB).
`serverLogs` is the installation's log lines of the requests the submitter made just before
submitting, when they chose to attach it: a JSON array of lines, oldest first, pseudonymised when the
telemetry setting of the connection it goes to is (ADR 0053 §1), whatever another connection's. Characters are UTF-16 code units, sizes are UTF-8 bytes. A value over its
limit is `400`, so the installation fits it before sending: it cuts text and drops what cannot be cut.

`requesterRef` is who submitted it, as the installation's **person reference** (ADR 0053 §3): `p-` and
16 lowercase hex digits, an HMAC of the user's id under a secret only the installation holds. It is
the same for every request of one person in that installation, so whoever handles requests can tell
that two come from one person, and never says who. Anything else there is `400`. Names and email
addresses of submitters are never sent.

`201` the first time, `200` for a repeat (the first content is kept):

```json
{ "requestId": "0193…", "url": null }
```

The service's **priority** starts as the requested one; staff change it, and the change reaches the
installation through the changes feed. The requested priority stays as the submitter set it.

### Add a message

`PUT {requests}/{requestId}/messages/{suiteMessageId}`

```json
{
  "kind": "REPLY",
  "authorName": "Jan Jansen",
  "authorEmail": "jan@voorbeeld.nl",
  "body": "…",
  "createdAt": "…"
}
```

- `QUESTION`: the request's description, sent right after the request; `body` required, the author
  is not sent (who submitted a request stays in the installation).
- `REPLY`: `body` required, and its author: `authorName` (with `authorEmail` optional), and
  `authorRef`, the author's person reference. While the installation keeps its tenants confidential it
  sends `authorRef` alone, and the service shows the reference as the author.
- `STATUS`: `status` required (`{ "kind": "STATUS", "status": "CLOSED" }`); it is the message's body
  too, and sets the request's status. Staff decide when the two sides disagree: it is not applied
  (the message is still stored) while a staff status change of this request is in the changes feed
  beyond what the installation acknowledged. The feed brings it, so both sides end with the staff
  value; once acknowledged, the installation's next status applies.

`authorName` is at most 200 characters, `authorEmail` at most 320, `body` at most 256 KiB.
`201`/`200` with `{ "messageId": "0193…" }`, idempotent on `suiteMessageId`.

### Attach a file

`PUT {requests}/{requestId}/attachments/{suiteAttachmentId}` — the raw bytes, `Content-Type` one
of `image/png`, `image/jpeg`, `image/webp`; optional header `X-Epistola-Filename`. At most 2 MiB.
`201`/`200` with `{ "attachmentId": "0193…" }`, idempotent on `suiteAttachmentId`.

### Changes from Epistola

`POST {requests}/changes` with `{ "acknowledgeUpTo": 40, "limit": 100 }` returns what Epistola
staff did — replies, status and priority set in the backoffice, never the installation's own writes —
for this installation only, in order after what it acknowledged. It works like collecting generated
documents (`/generation/collect`):

- **The service keeps the position**, per installation. The installation sends `acknowledgeUpTo`
  with its next call: the `sequence` of the last change it handled. The page that comes back starts
  after that. A change handed out but not acknowledged comes again, so an installation that handled
  part of a page acknowledges the last change it handled and gets the rest again.
- Both fields are optional; `limit` defaults to 100 and is clamped to 1..500. An acknowledgement never
  moves back, and one beyond the last change handed out, or negative, is `400 invalid_request`.
- An installation that handled a page acknowledges it on its next call, also when that call finds
  nothing new: it calls until a page comes back empty, so what it handled is acknowledged within
  the same round.

```json
{
  "changes": [
    {
      "sequence": 41,
      "requestId": "0193…",
      "tenantKey": "acme",
      "suiteRequestId": "…",
      "occurredAt": "…",
      "type": "MESSAGE",
      "message": {
        "messageId": "0193…",
        "kind": "REPLY",
        "authorName": "Epistola support",
        "authorEmail": null,
        "body": "…"
      }
    },
    {
      "sequence": 42,
      "requestId": "0193…",
      "tenantKey": "acme",
      "suiteRequestId": "…",
      "occurredAt": "…",
      "type": "STATUS",
      "status": "RESOLVED"
    },
    {
      "sequence": 43,
      "requestId": "0193…",
      "tenantKey": "acme",
      "suiteRequestId": "…",
      "occurredAt": "…",
      "type": "PRIORITY",
      "priority": "LOW"
    },
    {
      "sequence": 44,
      "requestId": "0193…",
      "tenantKey": "acme",
      "suiteRequestId": "…",
      "occurredAt": "…",
      "type": "ESCALATION",
      "reason": "Looks like a product bug"
    }
  ],
  "hasMore": false,
  "lastSequence": 44
}
```

`ESCALATION` asks the installation to escalate the request (ADR 0053 §4): it attaches the connection
the asking one escalates to, as its support desk would, while the tenant shares support requests with
that connection, and tells its desk in an internal note; when that cannot be done, the note says why.
The asking connection keeps the request; it learns of the other party's replies as of any reply.

Requests that start elsewhere (a support portal) will arrive here as their own change type; an
installation skips types it does not know and still acknowledges them.

A request id that belongs to another installation is `404`, never `403`.

## Catalog mirror — scope `mirror`

The mirror holds a copy of the releases an installation's tenants chose to share, so Epistola can
check them against new suite versions. It is not a registry: it names nothing, nobody resolves
names against it, and what an installation stops sharing is deleted. See ADR 0051.

An installation that keeps its tenants confidential (ADR 0052 §1) shares nothing here: a release holds
the templates themselves. It only withdraws, under the keys the service lists, what it shared before.

Everything is scoped to the calling installation. A content hash is only ever compared with the
same installation's releases, so the mirror cannot be used to learn whether another installation holds
some release.

### Reconcile a tenant

`PUT {api}/mirror/tenants/{tenantKey}` — the **complete** set of release bindings this tenant shares
now:

```json
{
  "bindings": [
    {
      "registry": "local",
      "namespace": "default",
      "key": "letters",
      "version": "1.3.0",
      "contentHash": "v6:9f2c…",
      "releasedAt": "2026-11-02T10:15:00Z",
      "deployments": [{ "environment": "prod", "production": true }],
      "dependencies": [
        {
          "registry": "exchange.epistola.app",
          "namespace": "acme",
          "key": "base",
          "version": "2.0.0",
          "contentHash": "v6:…"
        }
      ]
    }
  ]
}
```

The service replaces what it held for this tenant with this set, deletes every stored release of
this installation that no binding references any more, and answers which hashes it still lacks:

```json
{ "missing": ["v6:9f2c…"], "bindings": 12, "removed": 3 }
```

An empty `bindings` array withdraws the tenant: `200` with `bindings: 0` confirms that nothing of
it is held any more. `dependencies` name the releases a release pins; the suite sends each of those
releases as a binding of its own in the same set, so the mirror can render what it holds. `missing`
lists only hashes the service holds for no installation yet: content it already has, from this or
another installation, is not uploaded again. At most 10 000 bindings per request.

### Tenants held

`GET {api}/mirror/tenants` → `{ "tenants": ["acme", "hr"] }`. The suite withdraws (`PUT` with an
empty set) every tenant listed that it does not intend to share, which covers tenants deleted since
the last round.

### Upload a release

`PUT {api}/mirror/releases/{contentHash}` — the release archive exactly as the suite exports it
(`application/zip`), at most 20 MiB. Accepted only for a hash that one of this installation's
bindings references (`409 {"error": "not_announced"}` otherwise). `201` when stored, `200` when
already held. The content hash, not the archive's bytes, is the identity.

The service verifies the archive before it stores it, with the catalog contract's canonicaliser:
`400 invalid_request` when the body is not a readable catalog archive (also when it, or a release it
carries, expands to more than 20 MiB, the most a suite writes into one archive), `422 {"error":
"hash_mismatch"}` when the archive's content hash is not `{contentHash}`, and `422 {"error":
"unsupported_hash"}` when the service cannot compute that algorithm (a suite newer than the
service). Nothing is stored in any of these cases. The suite reports the release as failed and tries
it again in a later round, after its own back-off; `unsupported_hash`, like `507`, holds for every
release, so the suite stops uploading for that tenant at once and waits at least an hour. The
service verifies only a few archives at once; an upload that finds no turn within seconds gets `503`
with `Retry-After`, which the suite treats as "unavailable".

## Usage — scope `usage`

`PUT {api}/usage/hours/{hour}` reports one finished UTC hour (`{hour}` is its start, such as
`2026-11-07T09:00:00Z`; an hour not over yet is `400`) and replaces whatever was reported for that hour
before (ADR 0052 §6):

```json
{
  "tenants": [
    {
      "tenant": "t-3f9a0c1d2e4b5a69",
      "generated": 52,
      "previews": 3,
      "generatedTotal": 18250,
      "previewsTotal": 911
    },
    { "tenant": "hr", "generated": 0, "previews": 0, "generatedTotal": 40, "previewsTotal": 12 }
  ]
}
```

`tenant` is the tenant's key, or its reference while tenants are confidential. `generated` and
`previews` are the documents generated and previews rendered in that hour; `generatedTotal` and
`previewsTotal` are the running totals since the installation started counting, a meter reading.
Epistola bills from the totals: what they grew by since the tenant's previous reading. So a report that
never arrives loses nothing; the next one carries it. A tenant's first reading, its first after it was
missing from the installation's previous report (it stopped sharing for a while), and totals that went
down (a restored database) are a new starting point and bill nothing; staff see them flagged.

Every tenant that shares usage and has used anything is in every report, also with nothing in the hour;
every tenant while usage is Required, none while it is not allowed (then nothing is sent at all). An
hour without use is reported with an empty list, so Epistola can tell it from an hour that was not
reported. At most 10 000 tenants; a tenant listed twice, a negative count or an hour's figure above its
total is `400`. `200` with `{ "hour": "2026-11-07T09:00:00Z", "tenants": 2 }`.

The installation reports once an hour, at the same moment within the first ten minutes past every
hour, chosen at random when it first reports and kept, so the past hour arrives soon and installations
spread over those minutes. It waits until its statistics hold the whole hour, a few minutes at most. Hours missed while it was down
are sent when it is back, at most 72 of them; older ones are covered by the totals.

## Adding, retiring and granting services

The services change over time, and suites and the service are upgraded independently. These rules
keep every combination working.

- **Scope names are permanent.** A name is never renamed and never reused for something else. A
  new service is a new scope; a changed one that existing suites cannot use is a new scope too.
- **Unknown names are harmless on both sides.** The service ignores a requested name it does not
  offer and grants the rest; the suite asks only for selected names in `scopes_supported` and
  ignores a granted name it does not know. A suite newer than the service, or older, still gets a
  token for what both know.
- **Adding a service.** The service adds the scope to `scopes_supported` and its endpoints; staff
  can grant it. A suite that knows it offers it for selection; until an administrator selects it,
  nothing is sent. Older suites never ask for it.
- **Retiring a service.** The service first removes the scope from `scopes_supported`: suites stop
  asking for it and stop using it at their next metadata read (at most an hour), whatever the
  registration's grant still says, and show it as "no longer offered by Epistola"; tokens stop
  carrying it. Then its endpoints answer `404`. A suite's stored
  selection and its configuration may keep naming it; that is never an error. Once tokens no longer
  carry the scope, no installation can reach what the service held for it, so the service deletes
  that data when the endpoints go to `404`; the retirement notice says when. The suite keeps its own
  data (support requests, consent, mirror rounds) as it was.
- **Granting and withdrawing at runtime.** A grant reaches a selected service within one check
  interval (`epistola.support.check-interval`, 5 minutes): the check sees it, and the next token
  carries it. A withdrawal is refused at once by the service (`403 insufficient_scope`); the suite
  stops that service at once on every node (see Tokens) and confirms the withdrawal at its next check.
- **Deselecting.** An installation administrator's deselection stops the service at once on the
  node that saved it and within a second on the others, without waiting for a token to expire;
  the next token no longer asks for it.

## Reliability

The support service is optional. The suite treats it as a dependency that may be down, slow or wrong
at any time, and never lets that break or slow down the suite itself, lose local data, or hang a page
or a thread.

**Where calls run.** No call to the support service runs on a request thread or on the cluster
scheduler's poll thread (which also picks up document jobs and keeps `/actuator/health` UP). The
token refresh, the connection check, the support request sweep and reply poll, and the catalog-mirror round
run on a background worker of their own, one at a time per kind per node. The connection check, a
support request sending round and a tenant's catalog-mirror round also run on one node of the cluster at a
time, so two nodes never send the same thing. Sending a support request, a reply, a status and
adding a screenshot store the change and start a sending round in the background; while another
node's round runs, the node waits for it to finish (at most twice the round budget) and then sends
what is left, so nothing waits for the next sweep and nothing goes out twice. The support page's Check now, Register and Register again wait at most three seconds;
when the service is slower, the page says the check goes on in the background and refreshes itself.

**Timeouts.** Every call has a connect timeout (`epistola.support.connect-timeout`, 5 s) and a
deadline for the whole call (`epistola.support.read-timeout`, 30 s), covering a service that trickles
its answer or stops reading an upload; a request body adds a second per 512 KiB. An answer is read up
to 4 MiB; a larger one fails the call. A support request round spends at most
`epistola.support.requests.sync.round-budget-ms` (30 s), a mirror round at most
`epistola.support.mirror.round-budget` (5 min); what is left waits for the next round.

**Locks.** Check, Register and Register again take the cluster lock without waiting: when a check or
registration already runs on any node, a page says so at once and a scheduled check is skipped.

**Across nodes.** The nodes of an installation share one database and nothing else. The registration,
the last check's outcome, the selected services and the scopes the service refused (`403
insufficient_scope`) are rows every node reads; every write to them also raises a version counter
beside them. A node reads that counter at most once a second and reads the rows again only when it
changed (and every 30 seconds regardless), so a selection saved, or a scope refused, on one node
applies on every node within a second, at the cost of one primary-key read per node per second. That
is cheaper and sturdier than PostgreSQL `LISTEN`/`NOTIFY`, which needs a dedicated connection per node,
does not pass a pooler in transaction mode, and loses what is sent while a node is disconnected. A
refusal, a check and a token are ordered by that counter, never by their instants, which tie and
differ between nodes: a token remembers the version it read just before it was asked for, and settles
only refusals stored at or before that version; a check stores the version it read before it learned
the grants, and a token is newer news about them only when asked for at a later version. A node never
sends with a token that asked for another selection than the one in force (one fetched while another
node changed it included), nor with one that lacks a selected scope the installation reads as granted
once a check learned the grants after the token was asked for, or the token is 30 seconds old (another
node's token learned a grant this one predates): it asks for a new token first, and telemetry drops
that batch. A node that cannot read the stored refusals counts every scope as
refused until it can, and tries again after a second.

**Backoff.** A failed call (I/O, a deadline, `502`, `503`, `504`, `429`, an answer that cannot be
read: a success that is not JSON, or a body cut off) is "unavailable": the node records it, which
closes the services' gates until the next good check or token, and backs off its token requests for 30 s, doubling to 5 min; meanwhile a call that
needs a new token fails at once instead of waiting. A request never queues behind another thread's
token request. A `401` on a service call means the service no longer accepts the node's token (its
signing key changed, it restarted): the node replaces the token at once and retries the call once
(telemetry fetches the new token in the background and drops that batch), not at the next check. It
does so at most once per 30 s, doubling to 5 min, until a call shows a token accepted again (a
telemetry export the service takes counts too), so a
service that refuses every token never gets a token request per call; meanwhile the `401` is taken
as it is. A `401` never marks the registration refused: only the token endpoint's JSON
`invalid_client` does, and only once a node whose clock is off has confirmed it (see
[Expiry of unlinked registrations](#expiry-of-unlinked-registrations)). Items back off on their own:

| What                   | "Unavailable"                | Refused (`4xx`, `507`)                                      | `500` on one item                  |
| ---------------------- | ---------------------------- | ----------------------------------------------------------- | ---------------------------------- |
| Request, message, file | Round ends; nothing counted  | Retried after 1 min, doubling to 6 h; never given up        | Retried after 1 min; round goes on |
| Status change          | Kept unsent; round ends      | Retried after 1 min, doubling to 6 h                        | Retried after 1 min; round goes on |
| Mirror release         | Round ends; tenant backs off | Retried after the interval, doubling to 6 h                 | As refused                         |
| Mirror, every release  | —                            | `507`, `unsupported_hash`, `401`, `403`: tenant waits ≥ 1 h | —                                  |

A request refused five times in a row reads as FAILED, but is still sent once the service takes it.

**What is kept, what is dropped.** Support requests, their messages, files and status changes are stored in the
suite first and stay there until the service took them; a change made while the service is down is
sent once it is back. The mirror is rebuilt from the catalogs, so nothing is queued for it.
Telemetry is lossy by design: metrics and logs that cannot be sent are dropped, nothing builds up,
the log exporter makes one attempt per batch, and shutdown waits at most a second for each leg.

**What people see.** Platform → Support connections shows the last error and when the last check ran;
the connection's state (pending, active) is kept through any failure except the service's own
`invalid_client`. In the application log, each telemetry leg (logs, metrics) reports an outage once
per node: one WARN when its exports start failing, one INFO when they succeed again, and every
failure in between at DEBUG. An unreachable service, a deadline or an HTTP error is logged without a
stack trace, and the OpenTelemetry exporter underneath logs nothing about this leg's exports (an
installation's own OpenTelemetry export keeps its logging). A support request shows as waiting to be sent. Nothing a user does fails or waits because
the support service does.

## Versioning

This is v1, under `/api/v1` and the OAuth endpoints above, with support requests at v2
(`/api/v2/requests`). Within a version either side may add optional fields and endpoints. Removing or
changing one is the next version, served next to the previous one for as long as released suites use
it; the feedback endpoints of v1 were removed without that, as only the pilot used them.
