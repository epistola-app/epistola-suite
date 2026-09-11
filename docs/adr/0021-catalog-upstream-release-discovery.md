# ADR 0021: Discovering that a subscribed catalog has a newer release

- **Status:** Accepted — implemented
- **Date:** 2026-09-04
- **Context:** Installing catalogs from Epistola Exchange
  ([`docs/catalog-exchange-installation.md`](../catalog-exchange-installation.md))
- **Related:** [ADR 0017](0017-structured-failure-reasons.md) (failure reasons as data),
  [ADR 0018](0018-durable-catalog-publication-to-exchange.md) (the outbound direction)

## Context

A subscribed catalog is a mirror of somebody else's content. The question it raises constantly —
_has the publisher released anything newer?_ — had exactly one answer path: `CheckCatalogUpgrade`,
a live fetch made while the catalogs page rendered, lazy-loaded once per row. The initial state of
every row was literally `UNCHECKED`.

That was defensible when a catalog arrived by someone deliberately subscribing to a URL they knew.
It stops being defensible once catalogs are installed from a registry: nobody visits the catalogs
page to find out whether anything changed, so a release could sit unnoticed for as long as nobody
happened to look. Worse, the design could not answer the question at all for a catalog nobody was
looking at, which is every catalog most of the time.

Exchange also introduces a fact a manifest source cannot express: the release you are _running_ can
be withdrawn by its publisher. The content keeps working — it is local — but it is no longer
offered, and the way forward may be an _older_ release.

## Decision

**Poll on a schedule, persist the answer, and render from what was persisted.**

Four choices follow from that, and each had a plausible alternative.

### 1. Polled, not pushed

Exchange has no way to call Suite. A Suite installation is typically behind a firewall, has no
stable public address, and is frequently one of many. Even where a callback were possible it would
be a second delivery mechanism to secure, authenticate and make idempotent, for a signal whose
staleness budget is measured in hours.

Exchange does offer `GET /api/v1/upgrades`, a bulk feed of upgrades for catalogs a connection has
reported using. **We do not use it.** It requires the `USAGE` and `UPGRADES` scopes, and Suite
requests `read publish` at enrollment — so adopting it would force a reauthorization of every
existing connection. It also requires reporting which catalogs each tenant has installed, which is
a disclosure worth making deliberately rather than as a side effect of wanting a cheaper poll. The
alternative costs one request per catalog per interval: `getCatalog()` returns `latestVersion`,
already computed as the newest release anyone may install. Revisit if the number of installed
catalogs per tenant ever makes that arithmetic wrong.

### 2. Persisted, not fetched on render

A count in the navigation renders on **every page for every signed-in user**. It cannot make a
network call, and neither can a home-page banner. Persisting is what makes proactive notification
possible at all; rendering from a live fetch is what made the old design unable to offer it.

Persisting also decides behaviour on a bad day. An Exchange that is down leaves the page saying
what it last knew and when, rather than a spinner or a blank cell.

The state lives in **`catalog_upstream_checks`**, its own table, **excluded from tenant backups**.
It could have been columns on `catalogs`, which is smaller — but `catalogs` is in the backup set,
and restoring a backup would then resurrect knowledge about somebody else's server as it was months
ago: "update to v2.1 available" long after v3 shipped or that release was withdrawn. The worker
bookkeeping the table also carries (a claim lease, an attempt count, a next due time) has no
business on a row that browse, REST and MCP all read.

### 3. Two cadences, with jitter

The cluster task looks for due catalogs every **60 seconds**; any single catalog is asked every
**6 hours**. Conflating them would make the tick rate the request rate.

`next_check_at` is set to `interval * (0.85 + random() * 0.30)`. Without jitter, twenty catalogs
installed on one afternoon come due together every six hours for as long as they exist, turning a
routine check into a burst against the same source for ever.

### 4. Detection is automatic; applying is not

An upgrade replaces content people generate documents from. `ImportCatalogZip` guarantees a failed
resource abandons the whole upgrade — which protects against a _broken_ upgrade, not an _unwanted_
one. Only a person can judge the second, so the button stays.

### The seam

`CatalogUpstreamProbe` is a catalog-domain interface with two implementations: a manifest probe in
`catalog/`, and an Exchange probe contributed from `exchange/`. The worker resolves one per catalog
and knows nothing about what any of them talk to.

This is deliberately **not** a port in the style of `CatalogReleasePublicationPort`. That one exists
for a hard reason — publication intent must be written inside the release transaction, and
`ReleaseCatalogVersion` lives in `catalog`. Nothing here has that shape: the catalog domain never
needs to _call_ the Exchange integration, and the one case where it would have to (a deleted catalog
must forget its upstream state) is solved by an FK cascade. Schema, not code.

The probe is narrow on purpose. Installing is **not** shared: walking a manifest and importing an
archive are genuinely different engines, and an interface spanning both would be lossy enough to be
useless.

## Consequences

- Suite makes periodic outbound requests it did not before. Subscribing to a catalog is already a
  decision to talk to its source, and the on-demand check always made the same request — but this
  is unattended, so `epistola.catalog.upstream-check.enabled` turns it off and leaves the button.
- **No attempt limit.** Unlike the publication outbox, a check that keeps failing keeps retrying on
  a long backoff. Giving up would quietly stop telling anyone about upgrades, which is the one thing
  this exists to do; the failure is rendered on the catalog instead.
- An unreachable source is recorded and stepped past. It is never escalated, and one catalog's
  broken source never stops the rest being checked.
- **Exchange cannot warn about wire-format skew before a download.** `CatalogRelease` carries no
  catalog `schemaVersion`, so `availableSchemaVersion` is null for Exchange sources and a release
  published by a newer Suite is only rejected at import. Manifest sources keep their existing
  `SOURCE_BEHIND` / `SOURCE_AHEAD` states. Asking Exchange to expose the wire version on a release
  is the fix; until then the import-time message must say "upgrade Epistola" clearly, and the
  recorded failure must stop the row advertising an upgrade nobody can apply.
- The catalogs list no longer makes a request per row while rendering. `UNCHECKED` survives only for
  a catalog that has never been checked.
- Applies to **every** subscribed catalog, not only Exchange ones. A catalog subscribed from a URL
  gains background checking and proactive notification with no extra work, which is why the machinery
  lives in the catalog domain rather than the Exchange integration.
