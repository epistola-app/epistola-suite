# Catalog mirror

> **Status:** Alpha

The catalog mirror keeps a copy of the released catalogs a tenant chooses to share with Epistola,
held by the Epistola support service. Epistola uses it for one thing: to check those releases
against a new suite version before it releases one, so a customer's templates keep rendering after
an upgrade. [ADR 0051](adr/0051-catalog-mirror.md) records why it is shaped this way; the wire
protocol is in [Support service protocol](support-protocol.md#catalog-mirror--scope-mirror).

It is a custody copy, not a registry. Nothing is published through it, nobody resolves names
against it, and other customers never see it. What a tenant stops sharing is deleted there.

The mirror is a service of every support connection that grants it, not of Epistola's alone
([ADR 0053](adr/0053-support-connections-and-support-requests.md) step 3i): a partner signed in with
a token can keep one too. Each connection has its own tenant choices, preset, catalog policies,
round state and back-off (every mirror row carries its `connection_id`), and its own privacy
settings. A round runs per connection the mirror is selected for (`CatalogMirrorReconciler.reconcileEvery`),
so one connection that is unreachable or refuses never holds back another. The tenant page switches
between them (`?connection=`). The rules below hold per connection; "Epistola" stands for whichever
connection the tenant shares with.

## When it runs

All of these must hold:

1. The installation is connected to Epistola support (`epistola.support.enabled=true`, see
   [Support service protocol](support-protocol.md)), an installation administrator selected the
   catalog mirror under Platform → Support connections (or `epistola.support.services`), and Epistola
   has granted it the `mirror` scope. The tenant page says which of these is missing.
2. The installation keeps a mirror: `epistola.support.mirror.enabled` (default `true`).
3. A tenant administrator opted the tenant in, on **Support → Catalog mirror**.
4. The installation names its tenants to that connection. While it keeps them **confidential** (the
   default, [support](support.md#privacy)) the mirror is unavailable over it: a release holds the
   templates themselves, which name the organisation whatever key it is filed under. Making tenants
   confidential to a connection (the installation's settings, which Epistola support's connection and
   every connection without its own follow, or a connection's own) forgets every tenant's choice with
   it and its preset, and its next round withdraws everything its mirror holds.
5. Sharing catalogs is not **not allowed** by an installation administrator (ADR 0052 §5); making it
   not allowed withdraws every tenant at once.

With any of them missing nothing is sent. The page says which one is missing.

Withdrawals are the exception to point 2: with the mirror turned off, rounds still run and still
withdraw every tenant that opted out or was deleted, so turning the mirror off never leaves behind
what a tenant asked to have deleted. Tenants that still share are left as the mirror holds them.

## The controls

Opting in is an explicit choice between two options, neither preselected:

- **Every catalog, including new ones**, unless a catalog is marked never to be shared; or
- **Only the catalogs I mark** to share.

The page shows what is sent (below) before the choice, and the consent record keeps who agreed, when,
and to which version of that statement.

**Where a tenant sets it.** The choice is a step of the setup wizard on the tenant's **Support**
page, the one place a tenant sets what it shares ([Epistola support](support.md#what-each-tenant-shares)); this page shows the
state, points there, and keeps the per-catalog policies and Sync now. An installation administrator
can set it up for every tenant under Platform → Support connections (`SetCatalogMirrorDefault`:
do not share, share every catalog, or only those a tenant marks; `ClearCatalogMirrorDefault`), or the
deployment can (`epistola.support.tenant-defaults.mirror`, Helm `support.tenantDefaults.mirror`:
`off`, `all` or `marked`, applied at startup only while none is saved). It applies to every tenant
that has not chosen itself and to new tenants, recorded as that administrator's (or the
configuration's) opt-in to the current statement (`follows_default`); turning it off or removing it
withdraws those tenants at the next round.

Each catalog then has a policy: **Tenant's choice** (the default: inherit the choice above),
**Share** or **Never share**. Received and installed catalogs take a policy like any other; the
catalogs Epistola supplies cannot be marked Never share (see [What is sent](#what-is-sent)).

Tightening is easy, loosening is not:

| Action                                   | Needs                 | Role                 |
| ---------------------------------------- | --------------------- | -------------------- |
| Mark a catalog Never share               | `CATALOG_MANAGE`      | tenant administrator |
| Mark a catalog Share or Tenant's choice  | `INTEGRATIONS_MANAGE` | tenant administrator |
| Opt in, opt out, change the choice, sync | `INTEGRATIONS_MANAGE` | tenant administrator |

**Sync now** reconciles the tenant in the background straight away, instead of at the next round.

## What is sent

For each shared catalog, every release: its registry, namespace, key and version number, its content
hash, when it was released, the environments it is deployed to and whether those are production
environments, and the releases it depends on by name, version and content hash. The release archive
itself (the full content, including images, fonts and other assets) is uploaded only when the
support service holds that content hash for no installation yet.

**Dependencies go with it.** Epistola cannot render a release without the releases it depends on, so
each of those is sent too, all the way down, as a release of its own, also when its catalog is not
shared itself. The page says so on that catalog ("sent because a shared release depends on it").

**"Never share" wins.** A release that depends, directly or further down, on a release of a catalog
marked "Never share" is not sent at all; the page names the catalog that holds it back.

**Epistola's own catalogs** (the system catalog and catalogs bundled with the suite) are shared like
any other, but cannot be marked "Never share": every new catalog depends on the system catalog, so
withholding it would keep nearly every release home, and it holds nothing of the tenant's own. Their
content is usually held already, so it is seldom uploaded.

Never sent:

- drafts and draft deployments;
- generated documents and the data they were made from.

## How it reconciles

One node at a time runs the cluster task `support.mirror.reconcile`, `epistola.support.mirror.interval`
(default 15 minutes) after the previous round ended:

1. It asks which tenants the mirror holds (`GET /mirror/tenants`).
2. For every tenant that shares, it reads the tenant's bindings in short database reads and sends
   the **complete** set (`PUT /mirror/tenants/{tenant}`). An unchanged set is not sent again until a
   day has passed, unless the mirror no longer lists the tenant. The mirror answers which content
   hashes it lacks; an answer that does not state them (and `bindings`) counts as a failure.
3. It builds each missing release archive, one at a time, exactly as an export does, and uploads it
   (`PUT /mirror/releases/{contentHash}`). Before each upload it checks the tenant still shares: a
   tenant that opted out meanwhile gets no further upload, and the round withdraws it at once. It
   also checks the release's catalog is still shared (a catalog marked "Never share" meanwhile is not
   exported or sent; the next round's set leaves it out) and that the mirror can still be used here
   (deselected on any node, turned off or no longer granted meanwhile: nothing more is sent, the rest
   waits for a later round).
4. Every tenant the mirror holds that does not share — opted out, deleted, never opted in — gets an
   empty set, which withdraws it. Only an answer stating `bindings: 0` confirms the withdrawal.

Each tenant is claimed by one round at a time. A withdrawal, the one an opt-out schedules included,
waits for a round that holds the tenant to end (it tries again a minute later) rather than sending
an empty set that round could then overwrite.

No database transaction is open while the support service is called. When the service is
unreachable the round stops and the tenant backs off (the interval, doubled per failure, at most six
hours). A release the mirror refuses or fails on alone (a `500`), or that cannot be exported or is
too large, is recorded on the page and waits for its own back-off (the interval, doubled per round it fails, at most six hours)
before it is built and sent again; the rest of the round goes on. A refusal that holds for every
release (`507 quota_exceeded`, `422 unsupported_hash`, `401`, `403`) stops the tenant's uploads at
once and backs it off for at least an hour. A registration the service refuses (`invalid_client`)
stops the round without recording a failure against any tenant or release. A round runs off the scheduler thread, withdraws first,
spends at most `epistola.support.mirror.round-budget` uploading, and renews its claim on a tenant
after every upload; a round that lost its claim stops. A claim not renewed for longer than the
deadline of the largest upload plus four minutes (5 minutes 10 seconds with the default
`epistola.support.read-timeout`; longer when it is raised for a slow link) belongs to a node that died
mid-round: the next round on any node takes the tenant over, so a release made
meanwhile waits minutes, not half an hour. Claims are stamped and compared by the database's clock,
so a node whose own clock is off neither takes over a live round nor waits longer for a dead one. See
[support-protocol.md](support-protocol.md#reliability).

## Deletion

The mirror deletes, at the next round:

- every binding the complete set no longer names: a catalog marked Never share, a deleted release or
  catalog, a renamed one (its old name);
- everything of a tenant that opted out or was deleted;
- every stored release no binding references any more. Content is held once for all installations,
  keyed by content hash, so a release another installation still shares stays; this installation's
  bindings to it are gone either way, and Epistola no longer counts it as this tenant's.

After an opt-out the page shows **Stopped** until the mirror confirms it holds nothing of the tenant,
then the date it confirmed.

Withdrawing needs the support connection and the `mirror` scope. While the connection is off, the
mirror is deselected, or Epistola has revoked the scope, the suite cannot reach the mirror: a
withdrawal stays pending (the page keeps showing **Stopped**) and runs once the connection and the
scope are back. A revocation the service refuses with `403 insufficient_scope` stops the round at
once and counts no failure against the tenant. An installation administrator who
**disconnects** the installation (Platform → Support connections) ends it at once: the support service
deletes everything its mirror holds of the installation, and the suite forgets every tenant's
choice, the setup for every tenant and where each tenant's reconcile stood
(`ForgetCatalogMirrorSharing`); each catalog's own policy stays. Deleting what the mirror holds for
an installation that never comes back without disconnecting is up to Epistola.

## Configuration

| Setting                                | Default | Effect                                                                                               |
| -------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------- |
| `epistola.support.mirror.enabled`      | `true`  | `false` turns the mirror off for the whole installation: nothing is sent, but withdrawals still run. |
| `epistola.support.mirror.interval`     | `15m`   | Wait between reconcile rounds.                                                                       |
| `epistola.support.mirror.round-budget` | `5m`    | Most one round spends uploading; the rest goes next round.                                           |

For Epistola support's connection they need `epistola.support.enabled=true` to matter (off by
default); a partner's connection (`epistola.support.connections`) needs only `mirror` among its
services and granted.

## How to verify

- **Support → Catalog mirror** shows the connection, the consent record, the last sync (releases
  shared, uploaded, the last problem) and, per catalog, whether it is shared and when it last synced.
- The tests drive the reconcile against a stand-in support service over real HTTP:
  `CatalogMirrorReconcilerIntegrationTest` (complete sets, uploads of exactly the missing releases,
  withdrawals, the digest skip, back-off, Sync now, an opt-out during a round, the mirror turned off),
  `CatalogMirrorPerConnectionIntegrationTest` (a partner's mirror beside Epistola support's: what goes
  where, an unreachable partner, a withdrawal or confidentiality with one connection only) and
  `CatalogMirrorSharingIntegrationTest` (the sharing rules and what each binding carries).

## Why this is not in the demo catalog

The mirror is an integration between an installation and the Epistola support service, not an
authoring capability inside a catalog. No resource a bundled catalog could carry exercises it. The
behaviour is covered by the integration tests above and `CatalogMirrorPageHtmxTest`.
