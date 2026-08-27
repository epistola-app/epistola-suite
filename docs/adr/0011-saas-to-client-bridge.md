# ADR 0011: SaaS-to-client integration bridge for client-hosted Epistola

- **Status:** Accepted — Option A
- **Date:** 2026-07-20
- **Deciders:** Epistola team
- **Tags:** integrations, saas, networking, bridge, generation, catalogs, security

## Context

Some customer deployments run Epistola inside the customer's network, while the
business application that needs to use Epistola is a remote SaaS. That SaaS may
have an Epistola integration, but it usually cannot open network connections to
the customer's private Epistola instance. This blocks two integration needs:

- **Catalog/template discovery:** the SaaS must read what templates, variants,
  contract schemas, examples, and related catalog metadata are available.
- **Document generation:** the SaaS must submit document jobs, observe their
  status, and retrieve the PDFs for jobs it submitted.

Rendering alone could be modeled as a pull queue: the remote system submits a job
somewhere, and the customer-hosted Epistola pulls it and renders locally. Catalog
discovery is different because the SaaS wants live, request/response reads before
it can show template choices or validate input.

The suite already has relevant boundaries:

- External integrations belong on REST/MCP-style surfaces, not UI handlers.
- Per-tenant API keys already authenticate external systems and grant
  least-privilege roles.
- Generation is already asynchronous: `GenerateDocument` /
  `GenerateDocumentBatch` enqueue local jobs; local workers render; terminal rows
  are exposed through the generation result collection model.
- Existing support features already use customer-to-remote egress patterns
  (`epistola-support`, feedback polling, snapshot upload/download), proving that
  outbound-only connectivity is operationally viable.
- Generation input data is PII. Existing ADR 0009 deliberately keeps generation
  result feeds metadata-oriented and avoids duplicating input payloads into broad
  logs.

The bridge must therefore avoid inbound firewall requirements, keep generated
document data under tight scope, and still support live catalog reads.

## Decision Drivers

- **Customer network reality.** Many deployments can make outbound TLS
  connections but cannot expose inbound HTTP from a remote SaaS.
- **Direct when possible.** Avoid making the Epistola hub mandatory when the
  customer-hosted Epistola and SaaS integration gateway can communicate directly.
- **Reliable fallback.** Direct peer-to-peer connectivity cannot be guaranteed in
  enterprise NAT, proxy, and firewall environments; an explicit fallback is needed.
- **Least privilege.** The SaaS should read catalog information and submit/fetch
  only its own generation jobs, not receive a broad tenant API key or tenant-wide
  result feed.
- **Live catalog truth.** For v1, catalog discovery should be live. If Epistola is
  offline, the SaaS should show disconnected/unavailable state rather than browse
  cached templates.
- **Async generation.** Job submission and PDF retrieval may be asynchronous, with
  retry and reconnect behavior.
- **Data minimization.** The bridge must not replicate full catalogs or expose
  tenant-wide generation data unless a future ADR accepts that tradeoff.

## API plane vs. relay/event plane

The bridge decision has two layers that should stay separate:

- **API plane:** the stable application contract between the SaaS integration
  gateway and client-hosted Epistola. It defines operation names, request and
  response schemas, authorization, idempotency keys, error categories, audit
  events, data-minimization rules, and end-to-end encryption boundaries.
- **Relay/event plane:** the transport substrate that moves bridge envelopes
  between endpoints. It owns connection lifecycle, presence, routing,
  request/reply correlation, retry, reconnect, delivery cursors, queueing, and
  optional durable delivery.

The API plane is the product contract. The relay/event plane is replaceable
infrastructure. A relay technology such as NATS may route encrypted bridge
envelopes, advertise connection presence, and persist delivery state, but it
must not become the public bridge API itself. Bridge authorization remains an
Epistola application decision, even when the transport also enforces subject
ACLs, accounts, mTLS, or other connection-level controls.

This also keeps the bridge distinct from the existing support-tier Hub APIs.
Those APIs currently use gRPC/protobuf as a command/query and artifact-transfer
plane for registration, entitlements, feedback sync, snapshots, backups, and
health. NATS is most relevant where the system needs location-transparent
routing, reconnect/resume behavior, fanout, or durable async delivery. It is not
automatically a better replacement for every request/response or streaming
artifact operation.

## Considered Options

### Option A — Direct-first reverse bridge with encrypted relay fallback

The customer-hosted Epistola opens an outbound long-lived mTLS connection to the
SaaS integration gateway. The gateway sends allowlisted bridge operations over
that connection:

- live catalog/template reads;
- data-contract validation;
- async generation job submission;
- job status/result retrieval for jobs created through that bridge connection.

When direct connectivity from Epistola to the SaaS gateway is not possible or is
too operationally hard, the same protocol can run through an Epistola-operated
relay. The relay is an availability and routing component, not an authorization
or data-processing component. Operation payloads are end-to-end encrypted between
the SaaS gateway and the client-hosted Epistola, so the relay can route envelopes,
hold cursors, and report connectivity, but cannot read template metadata, input
data, or generated PDFs.

The bridge uses a dedicated connection identity, not a tenant API key handed to
the SaaS. Inside Epistola, that identity maps to a tenant-scoped principal with
only the permissions needed for the enabled bridge operations:
`TEMPLATE_VIEW`, catalog/reference reads needed for discovery, and
`DOCUMENT_GENERATE`.

**Pros:** works with outbound-only customer networks; avoids an always-required
hub; gives live catalog reads; keeps rendering local; supports offline/reconnect
for async jobs; avoids broad tenant API-key sharing; relay can be zero-knowledge
for business payloads.

**Cons:** introduces a new protocol and connection lifecycle; relay fallback
still requires an Epistola-operated network service; live catalog reads are
unavailable while Epistola is offline; bridge-scoped job tracking must be added
instead of reusing the tenant-wide collection feed directly.

### Option B — Generic network tunnel or VPN

Use WireGuard, Tailscale, SSH reverse tunnels, TUN devices, or a similar network
overlay so the SaaS can call the existing Epistola REST/MCP API as if it were on
the customer's network.

**Pros:** minimal application code; the existing REST/MCP API remains the primary
integration surface; arbitrary future endpoints become reachable.

**Cons:** high operational burden for customers; difficult to support uniformly
across enterprise networks; broadens network reach beyond the specific Epistola
operations needed; authorization remains tied to ordinary API credentials; "just
make TCP reachable" is a poor product contract for SaaS integrations.

### Option C — STUN/TURN/WebRTC-style peer connectivity

Attempt direct peer-to-peer connectivity using NAT traversal and fall back to a
TURN-like relay when needed.

**Pros:** can achieve direct paths in some network topologies; relay bandwidth is
used only when necessary.

**Cons:** enterprise firewalls and proxies often block or degrade these patterns;
adds substantial transport complexity; still needs relay fallback for the hard
cases; browser/media-oriented NAT traversal concepts do not remove the need for
application authorization, scoping, retries, and audit.

### Option D — Hub-managed catalog cache plus job queue

Client-hosted Epistola periodically publishes catalog/template metadata snapshots
to a hub. The SaaS reads catalog information from the hub and submits generation
jobs there; Epistola pulls jobs and pushes results.

**Pros:** good SaaS availability even when Epistola is temporarily offline;
simple SaaS-side query model; job queueing is natural.

**Cons:** replicates catalog/template metadata outside the customer deployment;
introduces freshness and invalidation semantics; increases data residency and
trust scope; conflicts with the v1 preference for live catalog truth.

### Option E — Direct inbound REST/MCP exposure

Require the customer to expose Epistola's existing REST/MCP API to the remote SaaS
with TLS, firewall allowlists, and tenant API keys.

**Pros:** no new transport; uses already-supported APIs.

**Cons:** often impossible under customer network policy; pushes security and
firewall configuration onto each customer; gives the SaaS a general API surface
rather than a narrow bridge contract; does not solve deployments that cannot
accept inbound connections.

### Option F — NATS/JetStream relay substrate

Use NATS as the relay/event-plane implementation for the bridge. Client-hosted
Epistola opens an outbound NATS connection, preferably directly to the SaaS
integration gateway's NATS endpoint and, when needed, to an Epistola-operated
relay. The bridge API remains an application envelope carried over NATS
subjects, with business payloads encrypted end to end between the SaaS gateway
and client-hosted Epistola.

The natural mapping is:

- Core NATS request/reply for live reads such as `ListCatalogs`, `GetTemplate`,
  `GetVariant`, and `ValidateTemplateData`. A disconnected Epistola yields a
  no-responder/timeout outcome that the SaaS surfaces as unavailable, preserving
  the live-only catalog decision.
- JetStream work streams for async generation submission, acknowledgements,
  redelivery after reconnect, bridge-scoped result notifications, and
  idempotent retry using `(bridge_connection_id, remote_request_id)` or a
  transport-level duplicate window.
- NATS connection state and service discovery for bridge presence, capability
  advertisement, and routing to the currently connected Epistola node.
- NATS accounts, users, mTLS, scoped credentials, and subject ACLs for
  transport isolation. These are defense in depth; the bridge identity and
  mediator principal still enforce the application permission boundary.
- Chunked payload streams or JetStream Object Store for PDF/result downloads and
  any larger envelopes that should not fit in a single message. This requires an
  explicit retention, expiry, encryption, and garbage-collection policy.

**Pros:** fits outbound-only customer networks; gives request/reply and async
delivery on one substrate; naturally supports reconnect, redelivery, presence,
and routing; can make the relay mostly a subject-routing and cursor component;
lets the same bridge operation model run direct or through a relay; can avoid
polling for result availability.

**Cons:** introduces a broker as a standing dependency for relay mode; requires
operating accounts, scoped credentials, subject design, stream retention,
consumer state, and observability; Core NATS is not durable, so stronger
delivery requires JetStream plus idempotent handlers; large payloads need Object
Store or chunking rather than ordinary request/reply messages; NATS ACLs do not
replace bridge authorization, audit, replay protection, or end-to-end payload
encryption; using NATS everywhere would couple the product contract to broker
subjects and stream topology.

This option is therefore attractive for the **relay/event plane**, especially
generation and reconnect/resume, but it should remain an implementation choice
under Option A rather than replacing the bridge API contract.

## Decision

**Accepted: Option A — a direct-first reverse bridge with encrypted relay
fallback.**

The bridge is an application protocol, not a generic network tunnel. Client-hosted
Epistola initiates outbound connectivity. The preferred topology is direct from
Epistola to the SaaS integration gateway. If that fails, the same bridge protocol
uses an Epistola relay. The relay may authenticate endpoints, route envelopes,
hold delivery cursors, and surface connection state, but business payloads remain
end-to-end encrypted between the SaaS gateway and the client-hosted Epistola.

The bridge API is transport-independent. It may initially be implemented over a
long-lived mTLS stream, gRPC bidirectional streaming, WebSocket, NATS, or another
substrate that satisfies the same contract. NATS/JetStream is explicitly kept on
the table as a strong relay/event-plane candidate, not selected as the bridge's
public contract and not a mandate to move existing support-tier Hub APIs off
gRPC.

Catalog/template reads are live-only for v1. When the client-hosted Epistola is
offline or disconnected, the SaaS must report the connection as unavailable
rather than use cached catalog data. This avoids catalog freshness policy and
metadata replication until there is a concrete product requirement for offline
browsing.

Generation is asynchronous. The SaaS submits work over the bridge, Epistola
enqueues it locally with the existing generation commands, local workers render
and store the document, and the bridge returns status/PDF content only for jobs
created through that same bridge connection.

The existing `/generation/collect` endpoint is not reused as-is. Its cursor model
is tenant/API-key scoped and intentionally exposes the tenant's generation result
feed to a consumer identity. A SaaS bridge needs a narrower result boundary:
fetch only jobs submitted by that bridge connection. The implementation therefore
adds a bridge-job mapping and bridge-scoped result queries while reusing the
existing local generation executor underneath.

## Consequences

- A new optional bridge module owns pairing, credentials, connection lifecycle,
  bridge operation authorization, and bridge job mappings. It is disabled by
  default.
- Bridge credentials and endpoint secrets are stored encrypted at rest using the
  existing credential-encryption infrastructure.
- Bridge pairing creates a dedicated non-human connection identity. The remote
  SaaS does not receive an ordinary tenant API key.
- Bridge operations dispatch through the mediator with a tenant-scoped bridge
  principal granting only the minimum required roles.
- Live catalog reads wrap existing catalog/template/variant/contract queries
  rather than introducing UI endpoints or ad hoc SQL paths.
- Async generation wraps `GenerateDocument` and `GenerateDocumentBatch`, then
  records `(bridge_connection, remote_job_id, tenant, local_request/batch)` so
  result fetches can be scoped to the originating bridge connection.
- Result retrieval returns metadata and PDF bytes only for bridge-created jobs.
  Tenant-wide generation result collection remains a separate REST API feature.
- The relay fallback must not log, inspect, or persist decrypted business
  payloads. Operational logs may record connection ids, tenant ids, operation
  names, sizes, timings, and non-PII error codes.
- If NATS is used for relay mode, subject names, stream names, consumer cursors,
  and Object Store buckets are internal deployment details. The stable bridge
  contract remains the operation envelope, authorization model, idempotency
  rules, and encryption boundary.
- The SaaS must handle disconnected live reads explicitly. No signed catalog
  cache, full template cache, or stale-read behavior is part of v1.

## Implementation Notes

The initial implementation should introduce a small bridge protocol with these
operation families:

- `Hello` / `Heartbeat` / capability advertisement.
- `ListCatalogs`, `ListTemplates`, `GetTemplate`, `ListVariants`,
  `GetVariant`, `GetDataContract`, and related reference reads needed for
  template selection.
- `ValidateTemplateData`.
- `SubmitGenerationJob` and `SubmitGenerationBatch`.
- `GetBridgeJobStatus`.
- `FetchBridgeGenerationResult`.
- Chunked `DownloadBridgeDocument`.

Each request carries a bridge connection id, remote request id, tenant key, and
operation name. Mutating operations also carry an idempotency key. Epistola must
deduplicate submit requests by `(bridge_connection_id, remote_request_id)` so
gateway retries do not create duplicate local generation jobs.

Relay mode uses the same operation model as direct mode. Only the relay/event
plane changes. If the relay uses NATS, the implementation should map live reads
to bounded request/reply calls, async generation to JetStream-backed commands or
notifications, and larger PDF/result payloads to chunked transfers or Object
Store references with explicit expiry and cleanup.

## Testing Requirements

- Unit-test bridge authorization: allowed operations pass; non-allowlisted
  operations and missing permissions fail before mediator dispatch.
- Unit-test envelope verification and replay/idempotency handling.
- Integration-test live catalog reads through the bridge against existing
  template, variant, and data-contract queries.
- Integration-test async generation end to end: submit, local render, bridge job
  status, result metadata, PDF download.
- Regression-test isolation: one bridge connection cannot fetch another
  connection's jobs, tenant-wide generation results, or arbitrary document ids.
- Reconnect tests: duplicate submit after reconnect returns the original bridge
  job mapping; pending result delivery resumes.
- Failure tests: offline Epistola makes catalog reads unavailable; generation
  submission fails or remains pending according to gateway state, but never
  creates unscoped tenant jobs.
- Transport-conformance tests for any relay implementation, including NATS:
  request timeout/no-responder mapping, duplicate generation submit handling,
  reconnect/resume, subject/stream ACL denial, payload encryption opacity at the
  relay, and cleanup of chunked/Object Store result payloads.

## Deferred

- Offline catalog browsing via signed metadata snapshots.
- Full managed catalog/template cache in a hub.
- Generic customer VPN/TUN support as a productized deployment mode.
- Final relay substrate selection for production bridge mode, including whether
  NATS/JetStream should be used directly by Epistola or hidden behind an
  Epistola-operated relay service.
- Write operations for templates, contracts, themes, stencils, or catalogs.
- Browser-facing bridge UI beyond pairing, status, and revocation.
