# ADR 0053: Support connections in the plural, and support requests

- **Status:** Proposed (rollout step 1 built)
- **Date:** 2026-11-08
- **Deciders:** Epistola team
- **Tags:** support, feedback, partners, saas, privacy
- **Related:** [ADR 0005](0005-feedback-storage-local-vs-remote.md) (feedback stored locally),
  [ADR 0050](0050-support-connection.md) (the support connection),
  [ADR 0052](0052-hosting-partners.md) (hosting partners; its §7 is replaced by this ADR),
  [`docs/feedback.md`](../feedback.md) (the host contract)

## Context

ADR 0050 connects an installation to **one** party, Epistola, and ADR 0052 shapes what that party
sees. Two kinds of contract use the suite:

- **Direct:** a customer contracts Epistola, whether it hosts the suite itself or Epistola hosts it.
  The customer's own application managers often want to see questions first; Epistola is second line.
- **Partner:** a partner hosts the suite for its clients, or supports a client who hosts it. The
  partner is Epistola's customer, its clients are not. The partner owns the support relationship,
  runs its own desk and portal, and must keep its clients confidential.

What the pilot does not cover:

- **Feedback goes only to Epistola.** For a partner, that bypasses its desk and sends its clients'
  free text to a party they have no contract with. Routing everything through Epistola and delegating
  to the partner would make Epistola hold that text even for an installation the partner runs: a
  detour with risk and no gain.
- **Most requests are handled in another system** (HQ at Epistola, a partner's desk), but the people
  working in the suite should get status and replies there.
- **Not every request starts in the suite.** When an installation is down, its people ask for help on
  Epistola's website, and that request belongs with the installation too.
- **"Feedback"** undersells it: most items are questions and problems.
- **Partners want what Epistola gets**, usage per client above all, to bill their own clients.

## Decision

### 1. The suite is the hub; it has support connections, in the plural

A **support connection** is a party outside the operation of the installation that the suite shares
with. Epistola is one; a partner is another. Every connection has:

- **who it is**: a name, an endpoint (discovered or configured), and how the suite signs in: the signed-key registration of
  ADR 0050, or a token the other side issues, set in configuration;
- **the services the other side offers** (discovery, `scopesSupported`), so a partner implements only
  what it wants to offer;
- **the services the installation selected** and the other side granted; used = selected and granted,
  per connection;
- **its own privacy profile** (ADR 0052 §1–2): tenants named or confidential, telemetry pseudonymised
  or everything. A confidential tenant has **one reference for every connection**, under the
  installation's one secret, so Epistola and a partner can talk about a tenant by the same reference;
- **its own sharing setting per service** (ADR 0052 §5): ask each tenant, on or off by default,
  Required, not allowed.

Every service can go over every connection: support requests, usage, telemetry, catalog mirror. A
partner that runs the installation will usually not take telemetry this way: it has the
installation's own logs and metrics (OTLP, Prometheus) already. Telemetry over a connection is for a
party that has no such access, such as a partner supporting a client who hosts the suite.

**Epistola is not a kind of connection.** Whatever seemed to set it apart is either something the
other side offers or something the installation configures:

| What looked Epistola-only    | What it is                    | Where it lives                                 |
| ---------------------------- | ----------------------------- | ---------------------------------------------- |
| found through product domain | where the endpoint comes from | `source`: discovered or configured             |
| registration and activation  | a capability of the server    | its discovery document; a partner may offer it |
| signed key or token          | how the suite signs in        | `auth`                                         |
| catalog mirror               | a service one side offers     | `scopesSupported`, like every service          |
| second line                  | an escalation order           | `escalates_to`, per connection                 |
| confidential by default      | a starting point              | the preset (below)                             |

So the code has no Epistola branch, every connection gets the same tested behaviour, and the
published protocol is the whole contract. An installation without an Epistola contract can connect
to its partner with everything the protocol offers.

The privacy shaping happens **in the suite, per connection**, whoever receives the data.

### 1a. Presets make a connection easy to set up

A **preset** is a starting configuration: endpoint (or where to discover it), sign-in, services to
select, privacy profile, sharing settings and escalation order. Once applied it is an ordinary
connection, and everything can be changed.

- **The Epistola preset ships with the suite.** One button on the platform page, **Connect to
  Epistola support**, or `support.enabled: true` in Helm, creates the connection from the product
  domain's discovery document (ADR 0030): signed-key registration, tenants confidential, telemetry
  pseudonymised, the services and tenant defaults from Helm. Registration then runs as today, on its
  own with `support.autoRegister`. The pilot's Helm values keep their meaning; they configure this
  connection. Creating it twice is not possible: the discovered connection is unique per endpoint.
- **Any server can publish its own preset** in its discovery document. **Add connection** asks for a
  domain, reads `/.well-known/epistola.json` there and fills everything in, so connecting to a
  partner is as easy as to Epistola. Without a discovery document the administrator fills in
  endpoint and token by hand.
- **Helm can declare further connections** (`support.connections`), each from a domain or by hand.
  Helm sets a connection until an administrator saves it, as with the privacy settings.
- Adding a connection while another exists offers the escalation order (a partner escalating to
  Epistola, for instance) as part of the preset.

### 2. Feedback becomes support requests

The user, the code and the protocol say **support request**; the page is **Support**, and the
website's **support portal** is a place to submit one. "Ticket" stays the word inside the systems
that handle them. The module, tables, permissions and wire fields are renamed. Feedback is labelled
alpha and was not in use, so this breaks in a minor release, said so in the changelog, and its tables
are dropped with their rows rather than moved: support requests start empty.

### 3. The suite keeps its own copy of every request

Every request that concerns an installation is stored in it, wherever it started (ADR 0005). People
read status and replies in the suite. Without any connection, the installation's own **inbox** across
tenants handles requests: the suite needs no external system.

A request is a **list of messages** and **a status**:

- **Messages** (the question, replies, notes, attachments: screenshot, console log, server log) are
  only ever added, each with its own id. Syncing them is "what is new since my cursor", in both
  directions; a message sent twice is recognised, and nothing conflicts.
- **The status** is set only by whoever handles the request.
- **The requester**, towards a connection that sees tenants as confidential, is a **person
  reference** (`p-` and an HMAC of the user id under the same secret): whoever handles the request can
  tell that two requests come from one person without knowing who. Names and addresses stay in the
  suite; the desk inside the installation sees them.

### 4. One connection handles a request; escalation hands it on

Each request has one **handler**: the installation's own inbox, or a connection. The route follows
who operates and supports the installation:

| Situation                                      | Handler            | Escalation                         |
| ---------------------------------------------- | ------------------ | ---------------------------------- |
| Direct customer, hosts it or Epistola hosts it | Epistola, or inbox | inbox → Epistola                   |
| Partner hosts its clients                      | the partner        | partner → Epistola, from the suite |
| Partner supports a client who hosts it         | the partner        | partner → Epistola, from the suite |
| No support contract                            | the inbox          | —                                  |

- A setting per installation says who handles new requests; an inbox can pass everything on at once,
  which is the pilot's behaviour.
- **Escalation happens in the suite**: from the inbox, or the handler asks for it through the
  protocol. The request then goes to the next connection shaped by that connection's privacy profile,
  and its replies come back to the suite.
- **A connection answers only whoever handed it the request.** Epistola's replies to an escalated
  request reach the partner, never the partner's client directly.

### 5. One protocol, published

The request part of the protocol is the same for every connection: send new messages, fetch changes
since a cursor, set or receive status, escalate. Its client side is published with the suite as an
OpenAPI spec next to [`docs/support-api.yaml`](../support-api.yaml), so a partner builds one thin
adapter between it and its own desk (TOPdesk, Jira Service Management, anything), for all its
installations. The suite has one connector implementation; which endpoint it talks to is
configuration. The Epistola support service is Epistola's implementation and stays internal.

**The suite only calls out** (ADR 0050): it sends its changes and fetches the other side's, about once
a minute and less often while nothing changes. No connection calls into an installation, so it works
behind any firewall; a reply shows up within about a minute.

### 6. Requests that start outside the suite

Epistola's website gets a **support portal**, on the support service, for those with a contract with
Epistola: direct customers and partner staff. A partner's clients use the partner's own portal, which
can open requests through the same protocol.

- **Signing in** is with the person's Epistola account in Authentik. The account belongs to an
  organisation, the organisation to its installations; the request is filed under the installation
  chosen.
- **The tenant** is chosen, or given as its reference for a confidential installation (`t-…`, ADR
  0052 §1). The suite computes its own tenants' references, so it maps one back locally; Epistola
  still does not learn which tenant it is.
- **The person:** when the address matches a user of the installation, the request appears with that
  user; otherwise in the installation's inbox.
- The suite fetches the request on its next sync, like a reply. A request from partner staff on
  Epistola's portal is an escalation: Epistola reads it by design.

## Consequences

- One model covers direct customers, partner SaaS and partners supporting a self-hosting client:
  they differ only in which connections exist and what each gets.
- A partner's clients' requests never pass through Epistola unless the partner escalates them.
- Partners get usage per client from the same code that reports to Epistola.
- The suite works as its own support desk, with or without a contract.
- Settings multiply (connections × services × tenants). Profiles for new connections, and tenants
  seeing only what they may decide, keep that in hand; the platform page needs a design pass.
- A tenant's choice and the installation default are one pair of tables for every service
  (`support_sharing_choice`, `support_sharing_default`, keyed by connection and service, beside
  `support_sharing_restriction`), so a new connection or service adds rows, not tables (built).
- Every table and job that assumes one connection gets a connection id: registration, tokens,
  consents and defaults, sending rounds, the usage cursor, the telemetry exporter.
  `DefaultSupportConnection` splits up.
- A partner implements a server side; starting with requests alone keeps that small.

## Rollout

1. Multiple connections in the model and the database, with Epistola as the only one: no change in
   behaviour. **Built:** `support_connections` holds each connection's registration,
   status, services and contact; restrictions and usage are per connection; feedback and the mirror
   use the discovered connection until step 2.
2. Feedback renamed to support requests; messages as an add-only list with a cursor; the inbox.
   - **2a, built:** the rename (module, tables, permissions, routes, toggle, properties).
   - **2b, the message model and protocol v2** (protocol v2, the client on it and requested and
     final priority built; the local message model and deliveries built):
     - _Requests_ keep title, kind (the category), status, the submitter's **requested priority**
       and the handler's **priority** (the final one), and the context sent once with them (page
       address, console log, server log, browser metadata).
     - _Messages_ (`support_request_messages`): QUESTION (the description), REPLY and STATUS, in a
       local `seq` order, each with the grant it was written under; one from a connection carries
       that connection and its remote id, unique together. Screenshots attach to the request.
     - _Deliveries_ (`support_request_deliveries`): one per request per connection, with the remote
       reference, the back-off, and the status not yet taken. What each connection has of the
       messages and attachments is a row per item (`support_request_delivered_items`), not one
       `sent_until` counter: every item keeps its own back-off, so one the connection refuses never
       holds back the ones behind it, as before (built).
     - _Sync_, per connection under the existing lease: send each due delivery (create, then
       messages and attachments not yet delivered), then fetch the connection's changes. The
       connection keeps the position, as with collecting generated documents: each call
       acknowledges what the previous page brought (`acknowledgeUpTo`), so the suite stores no
       cursor and every connection has its own (built).
       Consent rules stay as they are, read through the message's grant.
     - _Protocol v2_ (scope `requests`; v1 and `feedback` removed): `PUT /requests/{tenant}/{id}`,
       `PUT /requests/{remoteId}/messages/{id}`, `PUT /requests/{remoteId}/attachments/{id}`,
       `POST /requests/changes` with `acknowledgeUpTo`. The service's changes are its staff's replies, status and
       priority, and later requests that started elsewhere.
     - _Status_ until the inbox exists: a STATUS message from the suite is applied by the service as
       today; the handler rule is enforced with the inbox (2c).
   - **2c, built:** the inbox (Platform → Support requests, for the `SUPPORT_DESK` platform role),
     internal notes (message kind `NOTE`, never sent), the
     handler per request with only the handler setting status and priority, who handles new
     requests (the inbox by default; configuration `epistola.support.requests.handled-by` until an
     administrator saves a choice), escalation from the inbox, and polling every minute that backs
     off to five while nothing changes. Sending and polling still go over the one connection
     requests use; per connection comes with partner connections (step 3), the first time there is a
     second one to send to.
   - **2d, built:** person references (`p-`, an HMAC of the user id under the installation's
     secret) on every request as `requesterRef` and on every reply written in the installation as
     `authorRef`; while tenants are confidential a reply carries the reference alone. The support
     service shows staff the reference and that person's other requests.
3. Partner connections: requests and usage, the published spec, token sign-in.
   - **3a, built:** connections signed in with a token the other side issued
     (`SupportConnectionAuth.BEARER_TOKEN`), from `epistola.support.connections` (Helm
     `support.connections`, the token from a Secret), named by their endpoint, checked with
     `GET {endpoint}/api/v1/connection`, listed on the platform page. Independent of
     `epistola.support.enabled`.
   - **3b, built:** privacy per connection. Epistola support's stays the installation's
     (`app_metadata` `support.privacy`); another connection's is `support_connections.privacy` as an
     administrator saved it, else its configuration, else the installation's.
     `TenantReferences.onWire(tenant, connection)` says what a connection is sent for a tenant.
   - **3c1, built:** sharing per connection. A tenant's consent for support requests, the installation
     default and the restriction are read and written per connection
     (`GrantSupportRequestSyncConsent(tenant, connection)` and the rest); the tenant's Support page
     and the platform page have a panel per connection besides Epistola support. Sending still goes
     over Epistola support's connection only (3c2).
   - **3c2, built:** support requests sent and polled per connection. Who handles new requests and
     the desk's pass-on take any connection selected for support requests. For a connection other
     than Epistola support's, a request goes while the tenant shares with it, with what it held, and
     an item goes when it has a row in that connection's ledger (`support_request_delivered_items`
     without `remote_id`/`sent_at`), written while the tenant shared with it; a withdrawal drops the
     unsent rows. Epistola support's connection keeps reading the grant an item was written under. A
     port per connection (`SupportRequestSyncPorts`), a round and a poll per connection.
4. The support portal on Epistola's website, with Authentik.

## Deferred

- Assistance by AI at a desk or for users: model-independent by principle, to be designed separately.
- Rate limiting at the support service's gateway (support service roadmap).
