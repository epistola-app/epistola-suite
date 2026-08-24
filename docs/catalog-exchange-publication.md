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
    poll-interval-ms: 5000
```

Helm values:

```yaml
exchange:
  enabled: true
  discoveryUrl: https://epistola.app/.well-known/epistola/exchange.json
```

The chart maps these to `EPISTOLA_EXCHANGE_ENABLED` and
`EPISTOLA_EXCHANGE_DISCOVERYURL`. The default remains off after an upgrade, so
installing a Suite release cannot unexpectedly create outbound traffic.

When `base-url` is absent, Suite reads the public discovery document. Version 1
has this shape:

```json
{
  "version": 1,
  "issuer": "https://exchange.epistola.app",
  "baseUrl": "https://exchange.epistola.app"
}
```

Suite then reads `<issuer>/.well-known/oauth-authorization-server`, verifies
that the returned issuer matches, and uses the advertised device authorization
and token endpoints. `base-url` bypasses only the public product discovery; OAuth
metadata is still discovered and issuer-checked.

## Tenant enrollment, exactly

Enrollment requires `TENANT_SETTINGS` and follows OAuth device authorization:

1. Open **Settings → Exchange** for the Suite tenant.
2. Select **Connect to Exchange**. Suite discovers Exchange and requests scopes
   `read publish`, sending the Suite installation id and the tenant's display
   name. If this is reauthorization, it also sends the existing Exchange tenant
   connection UUID.
3. Suite stores the device code encrypted and shows the user code plus Exchange
   verification link. The browser session does not receive machine tokens.
4. The administrator opens Exchange, signs in, selects an organization, reviews
   the requested scopes, and approves or rejects the connection.
5. Back in Suite, **Check authorization** polls no faster than Exchange's
   advertised interval. `authorization_pending` leaves the grant pending.
6. On approval, Exchange returns rotating access/refresh credentials and the
   same connection identity. Suite calls the authenticated tenant-context
   endpoint to obtain the organization and allowed namespaces.
7. Suite replaces the pending grant with one active tenant connection, encrypts
   both credentials, and displays Exchange's stable human reference such as
   `tc_01HW9TGZT1FCF9Y2CE4XP3Y79M`. The UUID remains the internal and protocol
   identity.
8. If exactly one namespace is allowed, Suite selects it as the tenant default.
   If several are allowed, the administrator must choose a default on the same
   Exchange settings page before an unbound catalog can publish.

There is exactly one Exchange connection per Suite tenant. Reauthorization
updates that row and asks Exchange to preserve its remote connection identity;
it does not silently replace the organization. Organization replacement is not
part of the first version.

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
catalog policy permits it.

## Worker state machine

| Local state     | Meaning                                                             | Archive retained? | Next action                                                    |
| --------------- | ------------------------------------------------------------------- | ----------------- | -------------------------------------------------------------- |
| `WAITING_SETUP` | Enrollment/default namespace is incomplete.                         | Yes               | Recheck setup without failing the release.                     |
| `READY`         | Namespace and archive are ready to submit.                          | Yes               | Submit with the stored idempotency key.                        |
| `SUBMITTED`     | Exchange accepted the request but has not made a terminal decision. | Yes               | Poll the remote submission.                                    |
| `RETRY`         | A transient local/network call failed.                              | Yes               | Retry with exponential backoff, capped at one hour.            |
| `ACCEPTED`      | Exchange validation/scanning/publication accepted the release.      | No                | Terminal success.                                              |
| `REJECTED`      | Exchange made a terminal content/policy rejection.                  | No                | Terminal; publish a corrected new version.                     |
| `FAILED`        | Exchange's processing attempt failed operationally.                 | Yes               | Terminal until an administrator selects **Retry publication**. |

The worker is a single-owner cluster scheduled task and also uses
`FOR UPDATE SKIP LOCKED` plus expiring `claimed_at` leases. Those two layers make processing
safe across replicas and recoverable after node loss. Remote work already
accepted by Exchange may continue there when the tenant feature is switched
off; Suite pauses its own queued/polling work until the feature is enabled
again.

An explicit retry of `FAILED` reuses the retained exact archive, clears the old
remote attempt, and assigns a new idempotency key. It remains safe even when the
working copy has moved on. `ACCEPTED`, `REJECTED`, queued, and in-progress
attempts cannot be duplicated.

## Credentials and authorization failures

Access tokens, refresh tokens, and pending device codes use the normal
`Secret` JDBI mapping and AES-256-GCM envelope described in
[Credential encryption at rest](encryption.md). The generic key-rotation
commands do not know Exchange table names. The Exchange domain contributes its
three credential columns through `EncryptedCredentialContributor`, alongside
the core catalog/code-list contributor.

The worker refreshes access tokens before expiry even when a tenant has paused
catalog publishing, provided the deployment gate is still on. Refresh tokens
rotate on use. A rejected refresh changes the connection to
`REAUTHORIZATION_REQUIRED`; HTTP 401 does the same. HTTP 403 changes it to
`BLOCKED`. Both stop useful submission work and surface the connection error on
the Exchange settings page. Reauthorize to recover the same connection.

Disabling the deployment gate stops all Exchange network activity, including
credential maintenance. It does not delete encrypted credentials or queued
archives, so re-enabling resumes safely.

## Backup and restore boundary

Portable tenant backups include the tenant default and the catalog policy and
namespace preference because those columns belong to the normal `tenants` and
`catalogs` authoring rows.

They deliberately exclude:

- `exchange_tenant_connections` and OAuth credentials;
- pending device authorizations;
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
2. Exchange's OAuth metadata advertises device and token endpoints for the same
   issuer;
3. **Settings → Features** has `catalog-publishing` enabled for the tenant;
4. **Settings → Exchange** shows an active connection, `read` and `publish`, an
   organization, allowed namespaces, and a default namespace;
5. the catalog page shows the intended policy and namespace preference/binding;
6. release a test version with publishing selected and confirm its history moves
   from `READY`/`SUBMITTED` to `ACCEPTED` or a visible terminal error.

Useful failure distinctions:

- `WAITING_SETUP` is configuration, not a failed release;
- `RETRY` is transient and automatic;
- `REAUTHORIZATION_REQUIRED` requires the device flow again;
- `BLOCKED` means Exchange denied the connection/scopes;
- `REJECTED` is a terminal decision about that immutable release;
- `FAILED` is manually retryable with a new remote attempt.

## Deferred work

- Add the release-level override and publication id to the stable REST API after
  compatibility design; UI behavior already exists ([#863](https://github.com/epistola-app/epistola-suite/issues/863)).
- Define the inbound Exchange browse/install/subscription experience separately.
- Define organization replacement/migration separately from reauthorization.
- Add MCP publication tools only with an explicit authorization and idempotency
  contract; MCP currently remains unchanged.
