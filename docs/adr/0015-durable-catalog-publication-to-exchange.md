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

The feature has four explicit controls: a default-off deployment gate, a
default-off Alpha tenant feature, a tenant publish default, and a five-state
catalog policy with release overrides only for non-hard policies. The first
resolvable publication binds the catalog immutably to an Exchange namespace.

Tenant enrollment uses OAuth device authorization with rotating refresh tokens.
Suite keeps one remote connection per tenant and reauthorizes that identity.
The UUID remains the protocol/database identity; Exchange also exposes a stable,
typed `tc_` Crockford Base32 reference for people and logs.

Generic encryption rotation consumes `EncryptedCredentialContributor`
declarations. Exchange owns its declaration of access-token, refresh-token, and
device-code columns; the rotation handlers contain no Exchange table names.

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
  already accepted remotely.
- A restored tenant must enroll again and deliberately publish an unchanged
  current release; restore cannot replay old remote side effects.
- Catalog release code knows about the publication application boundary because
  it owns the atomic outbox write. Credential rotation remains integration-
  agnostic through the contributor SPI.
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
