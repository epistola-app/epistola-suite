# Publishing catalogs to Epistola Exchange

Epistola Suite can publish immutable releases of locally authored catalogs to
Epistola Exchange. Publishing is opt-in at both the deployment and tenant
levels, runs asynchronously, and never makes the local release depend on
Exchange availability.

This document is the canonical description of configuration, tenant enrollment,
setting resolution, namespace binding, publication processing, credentials,
failure handling, backup behavior, and the currently deferred API work. The
architectural rationale is recorded in
[ADR 0015](adr/0015-durable-catalog-publication-to-exchange.md).

## Scope

The implemented flow is outbound publication:

1. an operator permits the Suite deployment to contact Exchange;
2. a tenant administrator enables the Alpha feature and authorizes that tenant;
3. catalog/release settings decide whether an immutable release is queued;
4. a durable worker submits the exact release ZIP and follows Exchange's result.

This does not change the existing inbound catalog subscription, ZIP import, or
installation flows. Browsing Exchange from Suite and turning an Exchange release
into a Suite subscription remain separate product work. MCP is unchanged.

## The four controls

Publication is resolved from four controls, from broadest to narrowest:

| Level          | Setting                                                   | Default         | Effect                                                                                                    |
| -------------- | --------------------------------------------------------- | --------------- | --------------------------------------------------------------------------------------------------------- |
| Deployment     | `epistola.exchange.enabled`                               | `false`         | Hard network and UI gate. When false, no enrollment, credential maintenance, or publication traffic runs. |
| Tenant feature | `catalog-publishing`                                      | `false` (Alpha) | Enables publication behavior for one tenant. Turning it off pauses that tenant's unaccepted queued work.  |
| Tenant default | `publishCatalogsByDefault`                                | `false`         | Supplies the default for catalogs using `INHERIT`. It does not authorize network traffic by itself.       |
| Catalog policy | `INHERIT`, `ALWAYS`, `DEFAULT_YES`, `DEFAULT_NO`, `NEVER` | `INHERIT`       | Sets the catalog's default and whether a release-time override is allowed.                                |

The release dialog is the final decision point for policies that permit an
override:

| Catalog policy | Normal release default | Release checkbox | Meaning                                                        |
| -------------- | ---------------------- | ---------------- | -------------------------------------------------------------- |
| `INHERIT`      | Tenant default         | Available        | Follow the tenant unless this release says otherwise.          |
| `ALWAYS`       | Publish                | Not available    | Every new release is queued.                                   |
| `DEFAULT_YES`  | Publish                | Available        | Publish unless this release opts out.                          |
| `DEFAULT_NO`   | Do not publish         | Available        | Do not publish unless this release opts in.                    |
| `NEVER`        | Do not publish         | Not available    | Publication is forbidden, including “Publish current release”. |

The deployment gate and tenant feature must both be on before any policy can
queue work. Existing releases are not backfilled when either switch is enabled.
An unchanged current release can be queued explicitly from the catalog page;
Suite refuses that action when the working-copy fingerprint differs from the
release fingerprint, because publishing mutable content under an old version
would violate the release contract.

The stable REST release operation uses the resolved default policy. It does not
yet accept a per-release publication override or return the publication id. That
is tracked separately because changing a GA API needs its own compatibility
review. MCP has no publication operation.

## Deployment configuration

Application configuration:

```yaml
epistola:
  exchange:
    enabled: true
    discovery-url: https://epistola.app/.well-known/epistola/exchange.json
    # Optional local/private deployment escape hatch. Production normally omits it.
    base-url:
    # Optional browser-reachable redirect. Otherwise derived from the setup request.
    callback-url:
    # Worker cadence and remote-call bounds.
    poll-interval-ms: 5000
    connect-timeout: 5s
    read-timeout: 30s
    # Consecutive transient failures after which a publication becomes FAILED.
    max-attempts: 10
    # Recheck cadence for work that is not actionable yet.
    setup-retry-interval: 1m
    # Poll cadence for a submission Exchange has accepted but not decided.
    submitted-poll-interval: 30s
    # Refuse a plaintext Exchange. Only a local checkout should turn this on.
    allow-http: false
```

`base-url` and `callback-url` are optional: leave them blank (or omit them) to use
the discovery document and a request-derived callback. A blank value is treated
as unset.

Exchange must be reachable over HTTPS. The discovered issuer and base URL, the
OAuth endpoints, and the configured escape hatch are all checked, because the
application secret, refresh token and full catalog archive travel over them.
`allow-http: true` lifts that for a local Exchange checkout only — the `local`
profile sets it, and no other profile does.

Every Exchange call is bounded by `connect-timeout`/`read-timeout` and made
outside any database transaction, so a slow or hung Exchange cannot hold a
pooled database connection or block other readers of the connection row.

Helm values:

```yaml
exchange:
  enabled: true
  discoveryUrl: https://epistola.app/.well-known/epistola/exchange.json
  # Optional; otherwise derived from the initiating browser request.
  callbackUrl: https://suite.example.org/oauth/exchange/callback
```

The chart maps these to `EPISTOLA_EXCHANGE_ENABLED`,
`EPISTOLA_EXCHANGE_DISCOVERYURL`, and (when set)
`EPISTOLA_EXCHANGE_CALLBACKURL`. The default remains off after an upgrade, so
installing a Suite release cannot unexpectedly create outbound traffic.

When `base-url` is absent, Suite reads the public discovery document — the path a
real deployment takes. It is published by the Epistola website (an Astro route at
`src/pages/.well-known/epistola/exchange.json.ts`), and `ExchangeDiscoveryIntegrationTest`
serves that exact shape so the parser is pinned against the published contract
rather than against our own reading of it. Version 1 has this shape:

```json
{
  "version": 1,
  "issuer": "https://exchange.epistola.app",
  "baseUrl": "https://exchange.epistola.app"
}
```

Suite then reads `<issuer>/.well-known/oauth-authorization-server`, verifies
that the returned issuer matches, and uses the advertised authorization-request
and token endpoints. Those two endpoints are **stored on the connection**, so
later token refreshes call what Exchange advertised rather than reconstructing a
path Suite assumed. `base-url` bypasses only the public product discovery; OAuth
metadata is still discovered and issuer-checked.

The `local` Spring profile preconfigures `base-url` as
`http://exchange.localhost:4075` while leaving the deployment gate disabled.
It also sets the browser callback to
`http://localhost:4000/oauth/exchange/callback`.
Developers running the sibling Exchange checkout therefore only need to set
`--epistola.exchange.enabled=true`; local Suite instances never contact the
production discovery document merely because the local profile is active.

## Tenant enrollment, exactly

Enrollment requires `TENANT_SETTINGS` and uses redirect authorization with
state and S256 PKCE:

1. Open **Settings → Exchange** for the Suite tenant.
2. Select **Connect to Exchange**. Suite discovers Exchange, creates a
   high-entropy state and PKCE verifier, stores only the state hash and encrypted
   verifier, and registers its exact callback plus scopes `read publish`.
3. Suite returns HTTP 303 directly to Exchange. Exchange signs the administrator
   in and shows the tenant, callback host, requested scopes, and organizations
   they may administer.
4. The administrator selects an existing OAuth application with the exact same
   callback or creates one. They then create a tenant connection or select an
   existing one. **Recover credentials** is explicit: it rotates that
   application's secret and revokes its refresh tokens.
5. Exchange returns HTTP 303 to the exact registered Suite callback with a
   one-time code, unchanged state, client id, and issuer. The callback requires
   an authenticated Suite user with `TENANT_SETTINGS` for the tenant resolved
   from state.
6. Suite checks state and issuer, exchanges the code using the PKCE verifier,
   and receives the application secret (only when newly created or recovered),
   rotating access token, and refresh token through the backchannel. It then
   calls the tenant-context endpoint for organization and namespaces.
7. Suite replaces the pending transaction with one active tenant connection,
   encrypts the application secret and both tokens, and displays Exchange's
   stable human reference such as
   `tc_01HW9TGZT1FCF9Y2CE4XP3Y79M`. The UUID remains the internal and protocol
   identity.
8. If exactly one namespace is allowed, Suite selects it as the tenant default.
   If several are allowed, the administrator must choose a default on the same
   Exchange settings page before an unbound catalog can publish.

There is exactly one Exchange connection per Suite tenant. Reauthorization
offers the existing application and connection in Exchange. Selecting them
preserves the logical connection, publication authority, and entitlements.
If Suite has lost the application secret, the administrator explicitly enables
credential recovery. This invalidates credentials for every tenant connection
using that application, so separate applications are recommended for separate
Suite installations.

Suite need not have an internet-public URL. Exchange never calls the callback;
the administrator's browser does. The callback therefore only needs to be
reachable from that browser. Production Exchange requires HTTPS. Its local
profile permits HTTP for development. Behind a proxy, configure `callback-url`
explicitly if the browser-visible origin cannot be derived reliably from the
request and forwarded headers. Redirect URI wildcards are not supported.

Tenant administrators can disconnect a Suite tenant from Exchange from the
tenant's Exchange settings. Suite first authenticates to Exchange, which marks
the tenant connection revoked and invalidates all of its refresh credentials.
Only after Exchange confirms does Suite delete its locally encrypted
application secret, access token, refresh token, and any pending authorization
state. If Exchange is permanently unavailable, the administrator may explicitly
forget only the local credentials and must revoke the connection separately in
Exchange. Neither path deletes the organization application, publication
history, or immutable catalog namespace bindings. A later administrator-approved
authorization can reactivate the retained connection and application.

## Namespace selection and immutable binding

Exchange addresses a catalog by namespace, catalog key, and version. Suite
chooses the namespace in this order:

1. the catalog's existing binding;
2. an allowed catalog namespace preference;
3. the tenant connection's allowed default namespace;
4. otherwise no selection is possible and the job waits for setup.

The first resolvable publication transaction creates
`catalog_exchange_bindings(tenant, catalog, namespace)`. That binding is
immutable through the product commands. Later changes to the tenant default or
the catalog preference cannot move an already-published catalog to a different
Exchange coordinate. The catalog settings UI therefore disables the preference
after binding and shows the bound namespace.

If a release is queued before enrollment or before a multi-namespace tenant has
selected a default, its state is `WAITING_SETUP`. Connecting the tenant or
selecting a namespace is enough; the worker resolves and binds it later without
releasing the catalog again.

## Release and publication transaction

`ReleaseCatalogVersion` first builds and validates the authored catalog,
computes its canonical fingerprint, and constructs the same portable ZIP used
by export. When the resolved policy says publish, one database transaction:

1. inserts the immutable `catalog_releases` row;
2. advances the catalog's released-version pointer;
3. stores the exact ZIP in `catalog_release_publications` with a fresh
   idempotency key and `WAITING_SETUP` or `READY` state.

The release command reaches step 3 through `CatalogReleasePublicationPort`, a
catalog-owned seam handed the open transaction. Catalog decides _whether_ to
publish from its own policy; the Exchange side decides _how_ and _where_, so no
catalog code depends on the integration.

Only after that transaction commits can the cluster worker see the job. There
is no network call in the release transaction. Consequently:

- a local release succeeds while Exchange or public discovery is down;
- a process crash after commit cannot lose publication intent;
- a retry sends the retained bytes, not a reconstruction of a later working
  copy;
- the idempotency key makes an ambiguous submit retry safe.

`ImportCatalogZip` uses an internal `SUPPRESS` disposition so adopting an
imported release never republishes it accidentally. Stable REST release calls
use `DEFAULT`; the Suite release dialog can send `PUBLISH` or `SKIP` where the
catalog policy permits it. The dispositions are the `ReleasePublication` values
on `ReleaseCatalogVersion`.

## Worker state machine

| Local state     | Meaning                                                             | Archive retained? | Next action                                                    |
| --------------- | ------------------------------------------------------------------- | ----------------- | -------------------------------------------------------------- |
| `WAITING_SETUP` | Enrollment/default namespace is incomplete.                         | Yes               | Recheck setup without failing the release.                     |
| `READY`         | Namespace and archive are ready to submit.                          | Yes               | Submit with the stored idempotency key.                        |
| `SUBMITTED`     | Exchange accepted the request but has not made a terminal decision. | Yes               | Poll the remote submission.                                    |
| `RETRY`         | A transient local/network call failed.                              | Yes               | Retry with exponential backoff, capped at one hour.            |
| `ACCEPTED`      | Exchange validation/scanning/publication accepted the release.      | No                | Terminal success.                                              |
| `REJECTED`      | Exchange made a terminal content/policy rejection.                  | No                | Terminal; publish a corrected new version.                     |
| `FAILED`        | Exchange failed the attempt, or local retries were exhausted.       | Yes               | Terminal until an administrator selects **Retry publication**. |

The worker is a single-owner cluster scheduled task and also uses
`FOR UPDATE SKIP LOCKED` plus expiring `claimed_at` leases. Those two layers make processing
safe across replicas and recoverable after node loss. Remote work already
accepted by Exchange may continue there when the tenant feature is switched
off; Suite pauses its own queued/polling work until the feature is enabled
again.

Retrying is bounded. `RETRY` backs off exponentially, and after
`max-attempts` consecutive transient failures the publication becomes `FAILED`
and waits for an administrator. Every attempt holds the retained release ZIP, so
retrying forever would accumulate archives rather than make progress.

States that are simply not actionable yet — waiting for enrollment or a
namespace, or a tenant whose feature is paused — are **deferred**, not failed.
They are rechecked every `setup-retry-interval` and consume no retry budget, so
an unconfigured tenant does not spin at the poll interval. A submission Exchange
has accepted but not decided is polled every `submitted-poll-interval`.

The worker never loads a retained archive to decide what to do: claiming, polling
and retry accounting work from metadata alone, and the bytes are read only on the
branch that actually submits them.

An explicit retry of `FAILED` reuses the retained exact archive, clears the old
remote attempt, and assigns a new idempotency key. It remains safe even when the
working copy has moved on. `ACCEPTED`, `REJECTED`, queued, and in-progress
attempts cannot be duplicated.

## Credentials and authorization failures

The OAuth application secret, access and refresh tokens, and pending PKCE
verifier use the normal
`Secret` JDBI mapping and AES-256-GCM envelope described in
[Credential encryption at rest](encryption.md). The generic key-rotation
commands do not know Exchange table names. The Exchange domain contributes its
four credential columns through `EncryptedCredentialContributor`, alongside
the core catalog/code-list contributor.

The worker refreshes access tokens before expiry even when a tenant has paused
catalog publishing, provided the deployment gate is still on. Refresh tokens
rotate on use, and the rotated pair is written back under an optimistic check on
the row version that was read, so a concurrent refresh cannot be clobbered. A
rejected refresh changes the connection to `REAUTHORIZATION_REQUIRED`; HTTP 401
does the same. HTTP 403 changes it to `BLOCKED`. Both stop useful submission work
and surface the connection error on the Exchange settings page. Reauthorize to
recover the same connection.

Disconnecting revokes the remote connection first and only then removes local
credentials. That requires an active connection, so a broken one is dropped with
the explicit local-only recovery action instead.

Disabling the deployment gate stops all Exchange network activity, including
credential maintenance. It does not delete encrypted credentials or queued
archives, so re-enabling resumes safely.

## Backup and restore boundary

Portable tenant backups include the tenant default and the catalog policy and
namespace preference because those columns belong to the normal `tenants` and
`catalogs` authoring rows.

They deliberately exclude:

- `exchange_tenant_connections` and OAuth credentials;
- pending redirect authorization state and PKCE verifiers;
- immutable namespace bindings;
- publication outbox rows, retained ZIPs, retry state, and remote identifiers.

Those records describe an installation-specific trust relationship and remote
side effects. Restoring them elsewhere could duplicate a remote identity,
replay a publication, or bind a restored catalog to a namespace the new
installation does not control. After restore, an administrator enrolls the
tenant again and new releases follow the restored policy settings. Existing
releases remain local until explicitly published while their working copy still
matches.

## Operational checks

For a newly enabled installation, verify in this order:

1. the discovery URL returns version 1 and the expected issuer/base URL;
2. Exchange's OAuth metadata advertises authorization-request, authorization,
   and token endpoints for the same issuer, plus S256 PKCE;
3. **Settings → Features** has `catalog-publishing` enabled for the tenant;
4. **Settings → Exchange** shows an active connection, `read` and `publish`, an
   organization, allowed namespaces, and a default namespace;
5. the catalog page shows the intended policy and namespace preference/binding;
6. release a test version with publishing selected and confirm its history moves
   from `READY`/`SUBMITTED` to `ACCEPTED` or a visible terminal error.

Useful failure distinctions:

- a climbing `exchange_publication_oldest_active_age_seconds`, or the stalled
  warning on the Exchange page, means work is queued that cannot proceed;
- `WAITING_SETUP` is configuration, not a failed release;
- `RETRY` is transient and automatic;
- `REAUTHORIZATION_REQUIRED` requires redirect authorization again;
- `BLOCKED` means Exchange denied the connection/scopes;
- `REJECTED` is a terminal decision about that immutable release;
- `FAILED` is manually retryable with a new remote attempt.

## Watching it run

Publication is deliberately invisible on the happy path — a release succeeds
locally whatever Exchange does — so both surfaces below exist to make failure
visible instead.

**In the UI.** **Settings → Exchange** shows publication activity for the whole
tenant: a count per state, the most recently touched publications across every
catalog with a link back to each, and a warning when the oldest unfinished
publication has been queued for more than an hour. Per-catalog history stays on
the catalog page; this answers the question that page cannot, which is whether
anything is wrong anywhere.

**In metrics.** `epistola.exchange.publication.submissions{outcome}` and
`epistola.exchange.credential.refresh{outcome}` count what each node did.
Installation-wide state is published once per installation by an advisory-lock
elected replica: `epistola.installation.exchange_publications{status}`,
`epistola.installation.exchange_connections{status}`, and
`epistola.installation.exchange_publication_oldest_active_age_seconds`. Alert on
the last one — a queue age that keeps climbing is the single reliable signal that
publication has stopped working. See [`metrics.md`](metrics.md).

## Why this is not in the demo catalog

The demo catalog demonstrates authoring capabilities inside a catalog. Publication
is not one: it is an integration between a Suite installation and a separate
Exchange deployment, gated on an operator-enabled deployment switch and a tenant's
own OAuth enrollment. There is no resource a bundled catalog could carry that would
exercise it, and shipping demo content that points at a namespace no installation
owns would be misleading. The behaviour is covered by integration tests that drive
a stand-in Exchange over real HTTP instead (`CatalogPublicationWorkerIntegrationTest`,
`CatalogReleasePublicationIntegrationTest`, `DisconnectExchangeConnectionTest`).

## Deferred work

- Add the release-level override and publication id to the stable REST API after
  compatibility design; UI behavior already exists ([#863](https://github.com/epistola-app/epistola-suite/issues/863)).
- Define the inbound Exchange browse/install/subscription experience separately.
- Define organization replacement/migration separately from reauthorization.
- Add MCP publication tools only with an explicit authorization and idempotency
  contract; MCP currently remains unchanged.
