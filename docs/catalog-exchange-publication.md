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
| Permission     | `CATALOG_PUBLISH`                                         | publisher role  | Sending a release out of this installation, and choosing the namespace it lands in.                       |
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

A namespace chosen by mistake can be corrected until a release of that catalog has reached
Exchange; after that the catalog's published coordinates cannot move. That fact is recorded on the
binding, and the binding deliberately outlives the local catalog: Exchange keeps what was published
even after the catalog is deleted here, so a catalog recreated under the same key stays in the same
namespace rather than silently claiming a second one. Queued publications that have
not been submitted follow the catalog to its new namespace.

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
    # How long such a submission is followed before it is given up on.
    submitted-timeout: 24h
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

## One tenant, one organization

A Suite tenant connects to exactly one Exchange organization. The connection row carries the
credential, the organization the tenant publishes _as_, and the namespaces that organization grants
it. A provider authoring for several organizations uses one Suite tenant per organization; that is
already the boundary for catalogs, themes and API keys, so publishing identity follows it rather
than introducing a second, overlapping boundary.

Two rules keep that honest:

- **Reauthorization cannot change organization.** If an authorization returns a different
  organization than the one stored, Suite refuses it and keeps the existing connection. Every
  catalog binding names a namespace of the enrolled organization, and bindings are permanent once
  published, so adopting a new organization silently would strand all of them. Moving a tenant
  between organizations is a deliberate disconnect, and is otherwise deferred work.
- **A binding is re-checked against known grants, and a refusal repairs them.** Suite learns what a
  tenant may publish into only when that tenant authorizes: Exchange has no way to announce that an
  organization has withdrawn a namespace, and Suite does not poll for it. Between authorizations the
  local list can therefore be confidently wrong.

  So a refusal is treated as information. Before each submission the bound namespace is checked
  against the known grants — which catches it once Suite knows — and an HTTP 403 triggers a re-read
  of the tenant context before anything is concluded from it. If the namespace has gone, that is one
  catalog's binding: the publication is deferred with the reason recorded, no retry is spent, and the
  connection stays usable for every other catalog. If the namespace is still granted, the connection
  itself was refused and is marked `BLOCKED`. Either way the grant list is now correct, so the
  pre-submission check catches the next one instead of another refusal.

  Nothing is queued into a namespace the tenant no longer holds, and the catalog page says when a
  binding has been revoked — including whether there is anywhere left to move to. Publications
  Exchange has already accepted continue to be polled regardless, so an outcome is never lost.

## Several publishers, one namespace

Nothing stops two installations — or two tenants — publishing into the same namespace, and Suite
does not try to. It cannot see the other publishers, so reserving coordinates locally would be a
guess. Exchange arbitrates, and the release fingerprint is the material it needs: it travels inside
the manifest of every archive, so Exchange can treat a re-submission of identical content under the
same coordinates as the same release, and differing content under the same coordinates as a
conflict to refuse. Suite needs no additional wire field for this; the rule is Exchange-side and is
tracked separately.

## Namespace selection and immutable binding

A catalog's namespace is always chosen **explicitly**. Nothing infers one from the tenant default at
publish time: the choice becomes permanent the moment a release reaches Exchange, and a permanent
decision should not be made by a fallback. The tenant default is only the value a namespace picker
starts on.

Until a catalog has a namespace, **no publication is created at all** — a release with nowhere to go
is not queued and left waiting, it is simply not queued. The local release is unaffected either way.
The release dialog and the catalog's publish action offer the granted namespaces to anyone holding
`CATALOG_PUBLISH`, so choosing happens at the moment of publishing rather than on a settings page;
once chosen, the same forms show where the release will go instead of asking again.

Releasing and publishing are separate acts with separate permissions. `TEMPLATE_PUBLISH` cuts an
immutable release inside this installation; `CATALOG_PUBLISH` sends one to Exchange and decides the
namespace it lands in. Someone can be trusted with the first and not the second.

Only the **current, unmodified** release can be published after the fact, and that is deliberate. A
release is published exactly as it was cut. Suite retains the exact archive only for releases it
actually queued, so any other release has to be rebuilt from the working copy — which reproduces it
only while that copy still matches. Rather than publish something that merely resembles the release,
Suite refuses.

The consequence is worth stating plainly: a release that was never published, and whose catalog has
since changed, can never be published. The catalog page says so and names the version, instead of
quietly withdrawing the action. The way forward is to release the current state as a new version and
publish that.

Exchange addresses a catalog by namespace, catalog key, and version. The
namespace is recorded once in `catalog_exchange_bindings(tenant, catalog,
namespace)` and never inferred: someone holding `CATALOG_PUBLISH` picks it, from
the namespaces the connection grants. The tenant default supplies the value the
picker starts on and nothing more.

The binding is freely changeable until a release of that catalog reaches
Exchange. After that it can still be **moved**, but only deliberately: the change
must be acknowledged, because what it does is narrower than it looks. Versions
already published stay exactly where they are — Exchange holds them under the old
namespace and Suite neither can nor should move them — and only future releases
go somewhere new. The cost falls downstream: anyone following the old namespace
keeps the versions already there and never sees another one, with nothing telling
them the catalog continued elsewhere.

After a move the new namespace has published nothing, so the catalog is freely
changeable again until it does.

The binding is otherwise fixed from the moment a release reaches Exchange —
recorded on the binding itself as `published_at`, so the fact survives the
catalog being deleted, exactly as Exchange's copy of what was published does.
"Reaches Exchange" means **accepted**, not submitted: Exchange taking a
submission and then rejecting it leaves nothing published under those
coordinates, so a rejected first attempt leaves the catalog freely movable
rather than freezing coordinates it never occupied. A catalog recreated under the same key therefore returns to the
same namespace instead of claiming a second one.

A catalog with no namespace queues nothing. Its releases still succeed; they are
simply not sent anywhere, and can be published later once a namespace is chosen —
so there is never a queue of work waiting on configuration.

## Release and publication transaction

`ReleaseCatalogVersion` first builds and validates the authored catalog,
computes its canonical fingerprint, and constructs the same portable ZIP used
by export. When the resolved policy says publish, one database transaction:

1. inserts the immutable `catalog_releases` row;
2. advances the catalog's released-version pointer;
3. stores the exact ZIP in `catalog_release_publications` with a fresh
   idempotency key, `READY` to submit.

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

| Local state | Meaning                                                             | Archive retained? | Next action                                                                     |
| ----------- | ------------------------------------------------------------------- | ----------------- | ------------------------------------------------------------------------------- |
| `READY`     | Namespace and archive are ready to submit.                          | Yes               | Submit with the stored idempotency key.                                         |
| `SUBMITTED` | Exchange accepted the request but has not made a terminal decision. | Yes               | Poll the remote submission, up to `submitted-timeout`.                          |
| `RETRY`     | A transient local/network call failed.                              | Yes               | Retry with exponential backoff, capped at one hour.                             |
| `ACCEPTED`  | Exchange validation/scanning/publication accepted the release.      | No                | Terminal success.                                                               |
| `REJECTED`  | Exchange made a terminal content/policy rejection.                  | No                | Terminal; publish a corrected new version.                                      |
| `FAILED`    | Exchange failed the attempt, or local retries were exhausted.       | Yes               | Terminal until an administrator selects **Retry publication**.                  |
| `CANCELLED` | An administrator withdrew it before Exchange published it.          | No                | Terminal; the release can be queued again while the working copy still matches. |

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

**An unreachable Exchange does not spend that budget.** The budget exists to stop
one permanently-broken publication retrying for ever; an outage is not about any
publication in particular, and every queued release in the installation is
affected identically. Counting it would turn three quarters of an hour of downtime
into a pile of terminally failed publications for an administrator to retry one by
one. A connection or read failure therefore defers on the `setup-retry-interval`
cadence with the reason recorded, keeps its archive, and resumes on its own when
Exchange returns. The connection is not marked broken either — being unable to
reach a host says nothing about the credentials.

Failures that _are_ attributable to the request — an error response, or a reply
Suite cannot use — still count, which is what the budget is for.

**Following a submission is bounded separately.** Polling spends no retry budget,
because nothing has failed — which leaves it the one wait in the state machine
with no natural end. Exchange validates and scans on its own schedule, so a long
wait is legitimate and `submitted-timeout` is generous (24 hours by default); but
a submission Exchange never decides would otherwise be polled for ever while
holding its retained archive, visible only as a queue age climbing for no stated
reason. Past the timeout the publication becomes `FAILED` with that reason
recorded. It keeps its archive, so an administrator has both ways out — check it
in Exchange and retry, or withdraw it and release the bytes.

A state Suite does not recognize is deliberately treated as still in flight
rather than as a failure: Exchange may add an intermediate state, and a new one
must not break an older Suite. The timeout is what keeps that safe.

States that are simply not actionable yet — waiting for enrollment or a
namespace, or a tenant whose feature is paused — are **deferred**, not failed.
They are rechecked every `setup-retry-interval` and consume no retry budget, so
an unconfigured tenant does not spin at the poll interval. A submission Exchange
has accepted but not decided is polled every `submitted-poll-interval`.

The worker never loads a retained archive to decide what to do: claiming, polling
and retry accounting work from metadata alone, and the bytes are read only on the
branch that actually submits them.

A queued publication can be **withdrawn** from the catalog page until Exchange is holding it.
Withdrawing releases the retained archive and leaves the attempt in the history; the release can be
queued again afterwards, rebuilt from the working copy while that still matches. A `SUBMITTED`
publication cannot be withdrawn: Exchange may still publish it, so dropping it locally would abandon
the outcome rather than prevent it.

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

Portable tenant backups include the tenant publish default and each catalog's
publication policy, because those columns belong to the normal `tenants` and
`catalogs` authoring rows. The namespace binding is not among them: it names a
coordinate in an organization the restoring installation may not be.

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
5. the catalog page shows the intended policy and the chosen namespace;
6. release a test version with publishing selected and confirm its history moves
   from `READY`/`SUBMITTED` to `ACCEPTED` or a visible terminal error.

Useful failure distinctions:

- a climbing `exchange_publication_oldest_active_age_seconds`, or the stalled
  warning on the Exchange page, means work is queued that cannot proceed — during
  an Exchange outage this is the signal, since nothing fails and nothing is lost;
- no publication at all means the catalog has no namespace yet — releases succeed and simply are
  not sent, until one is chosen;
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
- **Ask Exchange about the impact of a namespace move before allowing it.** Exchange is
  the only side that can see whether the old namespace still has consumers. A move nobody
  depends on could then be waved through, while one that would strand subscribers could say
  how many. Longer term Exchange also needs a way to express "this catalog continues at
  `X`", so followers can be carried across rather than silently left behind. Until both
  exist, a move is allowed on an explicit acknowledgement and its downstream cost is stated
  rather than measured.
- Add MCP publication tools only with an explicit authorization and idempotency
  contract; MCP currently remains unchanged.
