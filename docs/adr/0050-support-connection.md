# ADR 0050: Support connection: self-registration, activation and scoped tokens

- **Status:** Proposed (pilot built)
- **Date:** 2026-11-05
- **Deciders:** Epistola team
- **Tags:** support, oauth, telemetry, feedback, discovery, outbound-traffic
- **Related:** [ADR 0005](0005-feedback-storage-local-vs-remote.md) (feedback storage, amended),
  [ADR 0006](0006-shipping-logs-and-metrics-to-hub.md) (telemetry to the hub, superseded),
  [ADR 0030](0030-product-domain-and-discovery.md) (product domain and discovery, amended),
  [ADR 0047](0047-quality-providers.md) (providers, egress and consent),
  [ADR 0051](0051-catalog-mirror.md) (the catalog mirror),
  [`docs/support-protocol.md`](../support-protocol.md) (the wire protocol, v1)

## Context

Customers are moving to production, and with that they need support from Epistola that works from
inside the suite:

- **Feedback**, submitted where the problem is, with its context (the page, the console, a
  screenshot), answered by Epistola and shown back in the suite.
- **Health checks**: before a suite version ships, Epistola checks the templates customers actually
  run against it. Until 29 October this was Upgrading, fed by tenant snapshots; both were withdrawn
  then, so 2.0 has no way to do it.
- **Logs and metrics** over OTLP, so Epistola sees a failing installation before the customer calls.

The suite already has a support tier for this, built against **epistola-hub** over gRPC:

- Registration returns a bespoke API key (`ek_…`) that the suite stores and sends with every call.
- What an installation may use is an entitlement list the suite fetches every six hours and resolves
  itself (installation-wide ALLOW, tenant-scoped DENY).
- Telemetry is OTLP over the hub's gRPC endpoint (ADR 0006), feedback a gRPC sync (ADR 0005), and the
  hub is found through `supportHub` (`host:port`) in the discovery document (ADR 0030).

Nobody uses it. The hub has no public deployment, it discards the telemetry it receives, and no
installation has a support contract that runs through it. It also costs:

- **gRPC through corporate networks.** Customer installations reach the internet through proxies
  that inspect TLS or speak HTTP/1.1 only. HTTP/2 with long-lived gRPC channels is the part most
  likely to be blocked, and the hardest for a customer's network team to diagnose.
- **A bespoke credential.** The `ek_` key is a bearer secret issued by the hub and held by both
  sides. A leak on either side is enough to act as the installation, and it never expires.
- **Entitlements decided twice.** The suite keeps a copy of the hub's entitlement rows, refreshes it
  on every node, and resolves it with its own rules. What the hub would refuse is guessed in the
  suite.
- **Leaks across tenants.** The tenant Support page lists the entitlement rows of every tenant to any
  tenant administrator.

2.0 is not GA, so `epistola.support` and its modules can change freely on the 2.0 line. Released 1.x
suites keep their hub client, which stays unused.

## Considered options

1. **Keep the hub and its gRPC protocol**, fixing what is broken. Rejected: the proxy problem is the
   transport, and the `ek_` key and the entitlement store are the design. Fixing them is this ADR.
2. **A generic service-provider model**: a directory of providers in `.well-known`, any party able to
   offer telemetry, feedback or mirroring, the suite choosing per service. Rejected: no provider other
   than Epistola is foreseen for any of these services, and the directory, provider selection and
   trust between them would be built for nobody. The protocol stays open (standard OAuth, OTLP, plain
   REST), so a second provider can be added when one exists.
3. **Exchange-style connections**: per tenant, with the authorization code flow and an Epistola user
   account that signs in and approves. Rejected for support. Exchange needs accounts because it is
   multi-party: a person picks the organisation and the namespaces a tenant publishes under, among
   many. Support is a bilateral contract between Epistola and one installation. Whoever operates the
   installation should only have to switch it on; checking who it is belongs at Epistola, which has
   the contract.
4. **Per-tenant feedback connections**, each tenant connected to Epistola separately. Rejected: in a
   hosted installation the hosting party is first-line support for all of its tenants, and holds the
   contract. Feedback is routed per tenant (every item names its tenant) but connected per
   installation. A per-tenant override can be added later without changing what exists.
5. **Chosen: one installation-level connection to one Epistola-operated support service**, set up
   by self-registration and activated by Epistola staff, authorised by short-lived scoped tokens.

## Decision

### 1. One support service, found through discovery

Epistola operates one **support service**, at `https://support.{product-domain}` by default. The
suite finds its issuer URL from `epistola.support.endpoint`, else from the `support` entry of the
discovery document (ADR 0030, amended below), else from that default; then reads its OAuth metadata
(RFC 8414). The protocol is fixed in [`docs/support-protocol.md`](../support-protocol.md).

Three services sit behind it, each a scope: `telemetry`, `feedback` and `mirror` (ADR 0051).

### 2. The installation connects, not the tenant

There is one connection per installation. It is switched on by configuration (Helm, application
properties) and looked after by the installation administrator (`INSTALLATION_ADMINISTRATOR`, ADR 0048) on a platform page. Tenants do not connect; they opt in to what sends their content (§9).

### 3. Registration with the installation's own key

When the support tier is on, an installation administrator registers the installation by pressing
**Register** under Platform → Epistola support; nothing leaves the installation before that.
`epistola.support.auto-register` (Helm `support.autoRegister`, off by default) lets the scheduled
check register on its own instead, for operators who want no manual step. Either way the
installation registers itself (dynamic client registration, RFC 7591):

- It generates an **EC P-256 key pair** once, stores the private key in `app_metadata` encrypted with
  the credential cipher ([`docs/encryption.md`](../encryption.md)), and sends only the public key.
- With it go the installation id from `app_metadata`, the suite version, a node id, and the operator's
  `epistola.installation.*` details (company, administrator email, environment), so staff can tell
  installations apart.
- The service answers with a `client_id` and a **claim code**.

No shared secret ever crosses the wire, in either direction. The service holds public keys only, so
a breach at Epistola exposes no credential an installation uses, and a database dump of an
installation exposes the key only together with its keyset.

### 4. Activation: a claim code, decided at Epistola

A new registration is **PENDING**: it gets tokens, but with no scope, and every service refuses it.
The suite shows the claim code to installation administrators only. The customer passes it to
Epistola through the channel the contract already has; staff find the registration by the code, activate
it to the customer and grant the scopes the contract covers. Staff can revoke a registration at any
time.

The friction is on Epistola's side, where the contract is. The installation needs no account, no
browser flow and no secret typed in.

**A registration nobody activates expires.** The service deletes a registration nobody activated within seven
days of issuing its claim code, so abandoned and junk registrations do not pile up. It says when, as
`epistola_claim_code_expires_at` in the registration answer and `claimCodeExpiresAt` in the
connection status, and the suite shows "valid until …" next to the code. The token endpoint then
answers `invalid_client` for the deleted client. The suite tells this apart from a revocation by its
own record: `invalid_client` for a registration it never saw activated is an expiry, for one it saw
ACTIVE a refusal. After an expiry it forgets the key and registers again at once with
auto-register; without it, the page says "The claim code expired on …" and offers Register. A
revoked registration is never replaced on its own: that stays the administrator's "Register again".

The service also answers `invalid_client` to an assertion dated outside its 60 seconds of skew, so a
node whose clock is badly off would otherwise read its clock as a revocation for the whole cluster.
When the answer's `Date` is further from the node's clock than that, the node asks once more with an
assertion dated by the service's clock: refused again, the refusal stands (a revocation is never
weakened); accepted, the node reports its clock as the problem, naming the difference, and marks
nothing refused or forgotten.

### 5. Tokens: client credentials with a signed assertion; the scopes are the entitlement

The suite gets access tokens with the client credentials grant, authenticating with a JWT signed by
its private key (`private_key_jwt`, RFC 7523). Tokens live minutes; an assertion five at most, with a
unique `jti` the service refuses to see twice.

**The scopes in a token are the entitlement.** The suite keeps no entitlement list and resolves no
rules: what it may use is what the last token said, and a service refuses what the token does not
carry. A grant or a revocation reaches the installation with its next token, within minutes, on
every node, without a refresh task. This replaces the entitlement store, its refresh and its revision
trigger.

**The installation chooses what it uses.** Epistola granting a service is not enough: an
installation administrator selects the services under Platform → Epistola support (or
`epistola.support.services` / Helm `support.services` until one saves a selection, after which the
saved selection wins). A service is used only while it is selected **and** granted. The selection is
stored installation-wide in `app_metadata` (`support.services`) and saving it needs no network. Every
token request carries `scope=` with exactly the selected services that `scopes_supported` lists —
empty for none — so a token never carries a service the installation did not choose. Deselecting
stops the service at once on the saving node and within a second on the others, without waiting for a
token to expire. There is no cluster broadcast: every write to the connection's rows raises a version
counter beside them, which each node reads at most once a second, reading the rows again only when it
changed. That costs one primary-key read per node per second and works through any pooler, where
`LISTEN`/`NOTIFY` would need a dedicated connection per node and a polling fallback for the time a
node is disconnected anyway. When the planned cluster cache-invalidation stream
(`docs/horizontal-scaling-phase1.md`, Phase 1C) exists, it can replace the counter. The service records what each installation last asked for and shows staff "asks for: …", so
they know what to grant.

**A refused scope stops the service, not the item.** The service checks a token's scopes against the
registration as it is now, so a scope staff withdraw is refused at once with `403
insufficient_scope`. The suite reads that as "not granted": the refusal is stored for the
installation, so the service stops on that node at once and on every other node within a second; its
local data stays (feedback stays pending, a mirror withdrawal stays pending), no failure is counted
against any item, the next token no longer carries the scope, and the next check confirms the grants.
A token asked for after the refusal that carries the scope again ends it for every node. Refusals,
checks and tokens are ordered by the version counter, never by instants, which tie and differ between
nodes.
About one refused request per installation (nodes refused in the same second each count one), not
one per item or round.

#### Adding, retiring and granting services

The catalogue of services changes, and suites and the service are upgraded independently, so the
lifecycle is part of the protocol (`docs/support-protocol.md`, same heading):

- **Scope names are permanent**: never renamed, never reused. A new service is a new scope.
- **Unknown names are harmless both ways.** The service ignores a requested name it does not offer
  and grants the rest (RFC 6749 §3.3) instead of refusing the whole token with `invalid_scope`; the
  suite asks only for names in `scopes_supported`, and ignores a granted name it does not know. An
  old service with a new suite, or a new service with an old suite, still issues tokens for what both
  know.
- **Retiring** removes the scope from `scopes_supported` first: suites stop asking for it, tokens
  stop carrying it, and the suite shows a selected one as "no longer offered by Epistola". Then its
  endpoints answer `404`, and the service deletes what it held for that service. Stored selections,
  configuration and Helm values may keep naming it: the suite logs such a name and ignores it, and
  the chart schema accepts it, so an upgrade never fails on it. The suite keeps its own data.
- **Granting** reaches a selected service within one check interval; **withdrawing** is refused at
  once, as above.

### 6. The suite always calls out, to one origin

The support service never calls an installation; an installation needs outbound HTTPS to one origin
and nothing inbound. The suite refuses OAuth metadata whose `issuer` differs from the URL it asked,
or whose endpoints are on another origin, and refuses plain `http:` unless the operator set
`epistola.support.allow-http` for local development. Assertions, tokens, feedback and release
archives go to that one origin only. A discovered issuer passes the same checks as every discovered
endpoint (ADR 0030: `https:`, the product domain or a subdomain).

### 7. A restored copy of an installation

A database restored into another environment brings the installation id and, if its keyset came
along, the key. It then presents itself as the same registration: the service shows staff which
nodes call under a registration, so a copy running beside production is visible, and staff decide.
A copy that cannot decrypt the key forgets it and generates a new one when it registers again (at its
next check with auto-register, otherwise when an administrator presses Register); the same installation id
with a different key becomes a **separate** PENDING registration and never replaces the existing
one.

### 8. Telemetry: OTLP over HTTP with a bearer token

Logs and metrics go to the service as OTLP/HTTP (binary protobuf), with the access token as a bearer
token. This **supersedes ADR 0006** in its transport (gRPC on the hub's endpoint) and its gating (the
`ek_` key and the `support-telemetry` entitlement); its first decision, that telemetry is gated per
installation and not per tenant, stands.

- **Installation-wide.** Telemetry is operational data about the installation: metrics (the `tenant`
  tag stripped) and log records. It carries no tenant content, so there is no tenant opt-in; the
  operator switches it, and the `telemetry` scope allows it.
- **Checked per export.** The suite sends only while its token carries `telemetry`, decided at each
  export, so a grant takes effect without a restart and a revocation stops the flow promptly.
- **The service stamps the identity.** It overwrites the installation id and client id on every
  resource with the values from the token, so an installation cannot report as another one.
- OTLP over HTTP passes the proxies gRPC did not, and Micrometer and the OpenTelemetry SDK both ship
  an HTTP sender, so the custom gRPC metrics sender goes.

### 9. Feedback: an installation connection, a tenant opt-in

Feedback keeps its local store as the source of truth and its sync port (ADR 0005); the sync target
becomes the support service, over REST, with idempotent writes keyed by the suite's own ids and a
poll for what Epistola staff did.

- **The connection is the installation's** (§2), and the `feedback` scope allows it.
- **Each tenant opts in**, by its administrator (`INTEGRATIONS_MANAGE`, the permission for settings
  that send tenant data out, ADR 0048), and the opt-in records **consent**: who, when, to what.
- **Consent is not the visibility toggle.** The `support-feedback` toggle decides whether the tenant's
  users see the feedback button; it defaults to on and says nothing about sending. Feedback is sent
  only when the tenant has opted in and the connection holds the scope; otherwise it stays local.
- **What was written while sharing was off is never sent.** Feedback, comments and screenshots
  record the consent in force when they are created. Withdrawing stops everything not sent yet.
  Giving consent again carries on with feedback Epistola already received, whatever grant it was
  submitted under; what was written in between stays in the installation.

### 10. Anything that sends tenant content needs the tenant's opt-in

The rule for this and later support services: what sends tenant content (feedback, release archives
for the mirror) needs the tenant to opt in, with recorded consent; what describes only the
installation (registration, telemetry) is the operator's decision. This matches ADR 0047, where an
external provider needs the operator's policy and the tenant's consent.

### 11. Out of scope

- **AI.** Language models working on templates are the reverse direction: the customer's own model,
  reaching the suite through MCP (`docs/mcp.md`) or configured as a provider (ADR 0047). They are not
  a support service.
- **REST and MCP.** The connection, the opt-ins and the sharing choices are configured in the web UI
  and Helm only. They are installation administration, not something an integration or an agent does.

## Consequences

- **Removed from the suite (2.0 line):** the `app.epistola.hub:client` dependency and with it gRPC
  and protobuf; the entitlement store, its refresh task and revision trigger; the hub health-check
  task and connectivity service; the gRPC telemetry sender and the `x-ep-api-key` header; the hub
  feedback adapter; `supportHub` in the suite's discovery model and its `hub.{product-domain}:443`
  default; the tenant Support page's hub and entitlement tables, and with them the cross-tenant
  listing. The test that aligned feature keys with the hub contract is gone: no feature key is tied
  to a scope any more (§5), and `SupportScopeTest` pins the scope names on the wire.
- **Configuration, breaking on the 2.0 line before GA.** New: `epistola.support.endpoint` (the issuer
  URL; blank means discover it), `epistola.support.allow-http`, `epistola.support.auto-register`
  (off by default: an administrator registers) and `epistola.support.services` (the initial
  selection, empty by default); Helm `support.endpoint`, `support.autoRegister` and `support.services`. Removed:
  `epistola.support.hub.endpoint`, `.plaintext` and `.node-id` (the node id is `epistola.node-id`),
  `epistola.support.health-check.*`, `epistola.support.entitlements.refresh-ms`, and Helm
  `support.hub.endpoint`. As in ADR 0030, an old name still set fails startup naming its replacement.
  `epistola.support.enabled`, `epistola.support.telemetry.*` and `epistola.installation.*` stay.
- **Data left in place.** `app_metadata` keeps the hub's `support.hub.credentials` and
  `support.entitlements`, unread; feedback rows a hub sync marked keep the hub's ids. Nothing is
  deleted (the stability contract forbids destructive migrations), and no installation used the hub,
  so nothing is migrated. A feedback cursor from the hub's sequence means nothing to the service.
- **New outbound traffic** to document in [`docs/outbound-traffic.md`](../outbound-traffic.md):
  registration (the public key and the installation details above), token requests, telemetry,
  feedback for opted-in tenants, and the mirror (ADR 0051).
- **Registration runs once per installation, not per node.** Every node must use the same key: the
  key is created and stored by one node, atomically, before anything registers.
- **Risk: losing the key.** The private key is only as safe as the credential keyset. Rotating the
  keyset without re-encrypting the key, or restoring without the keyset, leaves the installation
  unable to sign: it has to register again (on its own only with auto-register) and be activated again. The key's `app_metadata` entry must
  be covered by credential re-encryption.
- **Risk: tenant data in log records.** Telemetry carries no tenant content by design, but a log
  message can quote some (a URL, an exception message), and records name their tenant. The operator
  sets the minimum level shipped (`epistola.support.telemetry.*`); a per-tenant exclusion, which ADR
  0006 had through tenant-scoped DENY entries, has no counterpart in scopes and is not offered.
- **Risk: restored copies** report under the production registration until staff notice (§7). The
  service makes them visible; it does not prevent them.
- **The service is a standard OAuth server.** Libraries on both sides (Spring Security, Nimbus) do the
  assertion and token handling, and the same metadata checks as the Exchange client apply.
- **epistola.app keeps serving `supportHub`** in the discovery document for released 1.x suites, and
  adds `support`.

### Deferred

- The health-check runner and its results (ADR 0051).
- Rate limits and hardening beyond the pilot: registration is rate-limited per source address, and
  nothing else yet.
- Authentication of the support backoffice beyond the pilot's.
- A per-tenant override: a tenant connected to a support provider of its own, other than the
  installation's.

## References

- [`docs/support-protocol.md`](../support-protocol.md): the wire protocol, v1.
- [RFC 7591](https://www.rfc-editor.org/rfc/rfc7591): OAuth 2.0 Dynamic Client Registration.
- [RFC 7523](https://www.rfc-editor.org/rfc/rfc7523): JWT profile for client authentication.
- [RFC 8414](https://www.rfc-editor.org/rfc/rfc8414): OAuth 2.0 Authorization Server Metadata.
- [RFC 7638](https://www.rfc-editor.org/rfc/rfc7638): JWK thumbprint, the key id.
- [OTLP specification](https://opentelemetry.io/docs/specs/otlp/), "OTLP/HTTP".
