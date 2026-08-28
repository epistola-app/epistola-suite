# ADR 0015: Durable catalog publication to Epistola Exchange

- Status: Accepted
- Date: 2026-08-24

## Context

Suite authors catalogs and cuts immutable local releases. Exchange accepts,
validates, scans, and distributes those releases. Publishing crosses a network
and a product boundary, while release creation must remain a reliable local
operation. Exchange can be unavailable, a Suite node can stop after committing,
and a submission response can be lost after Exchange accepted it.

The feature also stores machine credentials. Generic credential rotation must
not gain knowledge of every optional remote integration, and portable tenant
backups must not reproduce remote trust or side effects.

## Decision

Suite records publication intent and the exact portable catalog ZIP in a local
transactional outbox in the same transaction as the release. A cluster-safe
worker submits the retained bytes using a stable idempotency key and follows the
remote submission to a terminal state.

The release transaction reaches the outbox through `CatalogReleasePublicationPort`,
a catalog-owned seam handed the open JDBI handle. Atomicity requires a shared
transaction, not a shared module: catalog decides _whether_ to publish from its
own policy, and the Exchange implementation decides _how_ and _where_. No catalog
code references Exchange, and the integration can be absent entirely.

**One Suite tenant is one Exchange organization.** The connection row is keyed by tenant, and it
carries three things at once: the credential for this installation, the organization identity the
tenant publishes as, and the namespaces that organization grants it. One credential per tenant is
uncontroversial; the identity is the load-bearing part. A tenant is already the isolation boundary
for catalogs, themes and API keys, so making it the publishing identity too keeps one boundary
rather than two. An organization authoring on behalf of several others therefore uses one Suite
tenant per client organization — which is what tenancy is for — rather than one tenant with several
connections.

Two consequences follow, and both are enforced rather than assumed. A reauthorization renews an
identity and may not replace one: an authorization returning a different organization is refused,
because every catalog binding in the tenant names a namespace of the organization it enrolled with.
And a binding is checked against the _current_ grants before each submission, because a namespace
recorded earlier is not proof that the organization still grants it.

Should multi-organization tenants ever be needed, `catalog_exchange_bindings` would have to
reference the connection rather than only the namespace. That is a migration worth knowing about in
advance; nothing is built for it now.

The feature has four explicit controls: a default-off deployment gate, a
default-off Alpha tenant feature, a tenant publish default, and a five-state
catalog policy with release overrides only for non-hard policies. The first
resolvable publication binds the catalog immutably to an Exchange namespace.

Tenant enrollment uses OAuth redirect authorization with state and S256 PKCE,
and rotating refresh tokens. Suite keeps one remote connection per tenant and
reauthorizes that identity. The authorization and token endpoints advertised by
the issuer are stored on the connection, so later token calls use what Exchange
published rather than a path Suite assumed. The UUID remains the
protocol/database identity; Exchange also exposes a stable, typed `tc_` Crockford
Base32 reference for people and logs.

Every Exchange call happens outside a database transaction and under an explicit
timeout. A remote call inside a transaction would hold a pooled connection — and,
with a row lock, block other readers — for as long as Exchange takes to answer.
Token refresh therefore reads, calls, and then writes back under an optimistic
check on the row version it read.

Generic encryption rotation consumes `EncryptedCredentialContributor`
declarations. Exchange owns its declaration of access-token, refresh-token,
application-secret, and PKCE-verifier columns; the rotation handlers contain no
Exchange table names.

Portable backups retain authoring preferences but exclude enrollment,
credentials, namespace binding, publication outbox, archives, retry state, and
remote identifiers.

## Consequences

- Local releases do not fail because Exchange is unavailable.
- Publication intent survives crashes and ambiguous HTTP outcomes are safe to
  retry.
- The outbox temporarily duplicates the exact release ZIP until Exchange
  accepts or rejects it; this is intentional durability, not a cache.
- Feature-off pauses queued work but does not revoke credentials or cancel work
  already accepted remotely. Pausing defers a row rather than failing it, so a
  paused tenant consumes no retries.
- Retrying is bounded. After `epistola.exchange.max-attempts` consecutive
  transient failures a publication becomes `FAILED` and waits for an
  administrator, because every retry holds the retained release ZIP and an
  unbounded queue of them is a storage leak, not resilience.
- A restored tenant must enroll again and deliberately publish an unchanged
  current release; restore cannot replay old remote side effects.
- Catalog release code knows only the port, not the integration behind it.
  Credential rotation stays integration-agnostic through the contributor SPI.
- A namespace chosen by mistake is correctable until a release of that catalog has reached
  Exchange, and permanent afterwards. Immutability exists to stop published coordinates moving;
  before anything is published it protects nothing, and freezing a typo forever is the worse
  failure.
- Several publishers may target one namespace, and Exchange arbitrates. Suite does not attempt to
  reserve coordinates it cannot see: the release fingerprint travels inside the manifest, so
  Exchange can treat a re-submission of identical content as the same release and differing content
  under the same coordinates as a conflict. That rule belongs on the side that sees every publisher.
- Stable REST and MCP contracts do not change in this delivery. REST override
  support needs separate compatibility work.

## Alternatives considered

### Publish synchronously during release

Rejected. Exchange latency and availability would control the local release
transaction, and a lost response would leave an ambiguous release outcome.

### Store only a release id and reconstruct the ZIP later

Rejected. The working copy can change after release. Reconstructing later risks
publishing bytes that no longer correspond to the immutable release and makes
recovery depend on mutable state.

### Emit an in-memory event after commit

Rejected. A process stop between commit and event handling loses publication
intent. Durable events could transport the signal, but the exact binary payload
and retry lifecycle still require durable feature-owned state.

### Call the Exchange integration directly from the release command

Rejected. It is the simplest way to get the outbox write into the release
transaction, but it points the dependency from the catalog domain at an optional
remote integration, and it spreads the deployment gate and the namespace rule
across every caller. Passing the open handle through a port keeps the atomicity
guarantee with none of that.

### Put Exchange columns directly in generic encryption commands

Rejected. It couples core key rotation to an optional integration and requires
editing central machinery for each credential-owning domain. Contributor-owned
column declarations keep the dependency direction correct.

### Include enrollment and outbox state in tenant backups

Rejected. Credentials and remote connection identity are installation-bound;
outbox restore can replay external side effects. Preferences are portable,
remote authority and runtime state are not.

## Detailed contract

The complete operational and product contract, including setting precedence,
namespace selection, enrollment steps, state transitions, retry behavior, and
backup boundaries, is maintained in
[Publishing catalogs to Epistola Exchange](../catalog-exchange-publication.md).
