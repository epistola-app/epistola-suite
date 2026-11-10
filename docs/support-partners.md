# Support connections for partners

> **Status:** Alpha. The protocol a partner implements to take support requests and usage from
> Epistola Suite installations (ADR 0053 step 3). It may change in a minor release while alpha.

A partner hosts the suite for its clients, or supports a client who hosts it. With a **support
connection** the installation sends the partner its support requests and its usage per hour, and the
partner's replies, status and priority come back into the installation. The partner builds one thin
connector between this protocol and its own desk (TOPdesk, Jira Service Management, anything) and uses
it for all its installations.

The machine-readable contract is [`support-partner-api.yaml`](support-partner-api.yaml) (OpenAPI 3).
It is the protocol of the Epistola support service ([`support-api.yaml`](support-api.yaml),
[`support-protocol.md`](support-protocol.md)) for what a partner implements: the same operations, so a
connector built for one serves the other.

## What to implement

All paths are relative to the connection's **endpoint**, which the partner chooses (for example
`https://desk.partner.example/epistola`). Every call carries `Authorization: Bearer <token>`.

| Call                                                         | What it does                                                         |
| ------------------------------------------------------------ | -------------------------------------------------------------------- |
| `GET /api/v1/connection`                                     | Whether the connection is active, and which services it grants       |
| `PUT /api/v2/requests/{tenantKey}/{suiteRequestId}`          | A new request; answer with your id for it (`requestId`)              |
| `PUT /api/v2/requests/{requestId}/messages/{suiteMessageId}` | A reply, the question, or a status change; idempotent per message id |
| `PUT /api/v2/requests/{requestId}/attachments/{id}`          | A screenshot, as bytes; idempotent per attachment id                 |
| `POST /api/v2/requests/changes`                              | Your changes since what the installation acknowledged                |
| `PUT /api/v1/usage/hours/{hour}`                             | One finished UTC hour's usage per tenant; the last report wins       |
| `POST /otlp/v1/logs`, `POST /otlp/v1/metrics`                | Telemetry, OTLP/HTTP (protobuf, gzip, delta temporality)             |

Offer only what you take: `GET /api/v1/connection` answers `{"status": "ACTIVE", "scopes": [...]}`
with the services you take (`requests`, `usage`, `telemetry`), and a call for a service you do not grant answers `403` with
`insufficient_scope`. A `401` tells the installation its token is no longer accepted; it then uses
nothing until a check succeeds again.

### The token

You issue each installation a token and its operator configures it (`epistola.support.connections`,
Helm `support.connections` with the token in a Secret) or enters it under Platform → Epistola support →
Other support connections. There is no registration and no OAuth flow. Rotate a token by issuing a new
one and having the operator replace it; the old one is refused from then on.

### Your discovery document

Publish `https://{your-domain}/.well-known/epistola.json` with a `support` object, and an operator only
has to enter your domain and the token:

```json
{
  "support": {
    "name": "Acme Partner",
    "endpoint": "https://desk.partner.example/epistola",
    "services": ["requests", "usage"],
    "tenantIdentity": "named"
  }
}
```

`tenantIdentity` and `telemetry` are suggestions; the installation's administrator decides.

## Tenants and people

Each connection has its own privacy settings. While tenants are **confidential** to you, every tenant
is sent as its reference (`t-` and 16 hex digits); while they are **named**, by its key. A reference is
the same for every connection of an installation, so you and Epistola can talk about a tenant by the
same reference. The person who asked is sent as `requesterRef` (`p-…`), and a reply written in the
installation carries `authorRef`: you can tell two requests of one person apart without knowing who.
Names and addresses stay in the installation.

## Requests

- **Who sends what.** A request reaches you when the installation hands it to you (you handle new
  requests, or its support desk passes it on) or attaches you besides its handler, and only while the
  tenant shares support requests with you. Everything written on it then goes along, except the
  installation's internal notes; afterwards, what is written while the tenant still shares with you.
- **Handler.** One party handles a request: the installation's own desk or one connection. Only the
  handler's status and priority are applied; an attached connection's are ignored, and it hears the
  handler's.
- **Your changes.** `POST /api/v2/requests/changes` with `acknowledgeUpTo` returns the next page after
  what the installation acknowledged: replies (`MESSAGE` with a `REPLY`), `STATUS`, `PRIORITY` and
  `ESCALATION`. You keep the position per installation; a change sent again is recognised by its ids.
- **Escalating.** An `ESCALATION` change (with an optional `reason`) asks the installation to escalate
  the request: it attaches the connection you escalate to (its operator sets that, often Epistola
  support), while the tenant shares with that connection, and tells its desk. You keep the request; the
  other party's replies reach you as any reply.

The installation only ever calls you. It sends its changes and fetches yours about once a minute, less
often while nothing changes, so it works behind any firewall.

## Usage

Each finished UTC hour is sent once per connection, with every tenant that shares usage with you, its
figures for the hour and its running totals. Bill from the totals: an hour that never arrives loses
nothing. Hours missed while the installation or your service was down are sent when both are back, as
far back as the installation's `epistola.support.usage.backlog` (72 hours by default).
