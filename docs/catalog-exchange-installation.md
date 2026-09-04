# Installing catalogs from Epistola Exchange

> **Status:** Alpha, off by default. The inbound half of the Exchange integration — browsing a
> registry and installing what it publishes. The outbound half (publishing releases _to_ Exchange)
> is [catalog-exchange-publication.md](catalog-exchange-publication.md).

A catalog installed from Epistola Exchange is an ordinary **subscribed** catalog: a read-only mirror
of somebody else's content, kept up to date deliberately rather than automatically. What Exchange
changes is where the content comes from and how a newer release is noticed.

## The controls

Four switches, and all of them must be on before anything can be installed.

| Control                                  | Where                 | Default | What it gates                                                                        |
| ---------------------------------------- | --------------------- | ------- | ------------------------------------------------------------------------------------ |
| `epistola.exchange.enabled`              | deployment property   | `false` | The whole integration, in both directions. Nothing reaches Exchange while it is off. |
| `catalog-installing` feature             | tenant, Features page | off     | Browsing and installing, per tenant. Separate from `catalog-publishing`.             |
| An active Exchange connection            | tenant, Exchange page | none    | Installing needs a token. See below.                                                 |
| `CATALOG_MANAGE` **and** `TEMPLATE_EDIT` | permissions           | by role | Who may install. Both, see [Permissions](#permissions).                              |

`catalog-installing` is deliberately **not** the same key as `catalog-publishing`. Publishing sends
this tenant's content out; installing brings a third party's in, through the schema migrator and
into templates people generate from. An operator can reasonably want one without the other — and
sharing a key would mean that switching off publishing silently stopped upgrade checks for catalogs
already installed.

## Why installing requires a connection

Exchange's browse and download routes are public today: anyone can list catalogs and fetch a release
archive without a token. Suite requires an active tenant connection anyway.

That is a deliberate choice, not a reading of what Exchange enforces. A connection is what gives an
install an identity and an organization, and Exchange is expected to require authentication before
long so that it can rate-limit. Building on the public routes now would mean rebuilding on the
authenticated ones later.

Connect a tenant on **Settings → Exchange**; the flow is the same one publishing uses and is
documented in [catalog-exchange-publication.md](catalog-exchange-publication.md#enrollment).

## Installing

**Catalogs → Browse Exchange.** Search, open a catalog, choose a version, install.

What happens then is the existing ZIP import in its subscribed mode, so the guarantees are the ones
that path already had:

- The catalog arrives **read-only**. Editing controls are hidden and mutating commands refuse.
- Resources are mirrored: what the release contains replaces what is there, and what it no longer
  contains is removed.
- **If a single resource fails to install, nothing changes at all** — no resources are pruned, no
  version is advanced, and the next attempt starts over. There is no half-installed state.

Before any of that, and before a byte is downloaded, an install can be refused for reasons worth
knowing about.

### The catalog ID

A catalog is addressed within a tenant by the slug in its own manifest. `acme/invoices` and
`globex/invoices` therefore both want to be `invoices` here, and both would be subscribed catalogs —
so nothing about their _type_ distinguishes them.

Installing the second is **refused**, naming what already holds the ID. It is not merged, and it does
not overwrite. Installing the same catalog under a different local ID is not supported: every
cross-catalog reference in the imported templates is written against that ID, so a rename would
produce a mirror that no longer matches its source.

If you need both, install them into different tenants.

### Releases that cannot be installed

Exchange marks a release `AVAILABLE`, `BLOCKED` (scanning has not passed) or `WITHDRAWN` (pulled by
its publisher, permanently). Consumers normally never see the last two — but Exchange keeps
returning them to the installation that _publishes_ the catalog, so a tenant that both publishes and
installs would see its own. Only `AVAILABLE` releases are ever offered.

### Size and integrity

A release larger than `epistola.catalog.max-zip-size` (**10 MB** by default) is refused from its
advertised size, before the download. The download caps itself as well, because that figure is
Exchange's claim about its own body.

The archive is verified against the SHA-256 published in the release listing — a different response
from the one carrying the bytes. A mismatch is refused outright.

## Updates

Installed catalogs are checked in the background: every subscribed catalog's source is asked, every
six hours by default, whether it has published anything newer. The answer is recorded, and the
catalogs list, the navigation count and the home-page notice all render from that record rather than
asking while a page loads.

This applies to **every** subscribed catalog, not only Exchange ones — a catalog subscribed from a
manifest URL gets the same treatment. Only the asking differs.

| Property (`epistola.catalog.upstream-check.`) | Default | Meaning                                                             |
| --------------------------------------------- | ------- | ------------------------------------------------------------------- |
| `enabled`                                     | `true`  | Turns background checking off. The per-catalog button still works.  |
| `poll-interval-ms`                            | `60000` | How often the worker looks for catalogs that are **due**.           |
| `interval`                                    | `6h`    | How long one catalog's answer is treated as current. Jittered ±15%. |
| `batch-size`                                  | `25`    | How many catalogs one tick checks.                                  |

Applying an update is always a deliberate click. Detection is automatic; the change is not.

A **withdrawn** release is reported, never acted on. The content keeps working — it is local — but
the catalog is shown as running something no longer offered, and the update it offers may be an
**older** release, because that may be the newest still available.

### What cannot be detected before downloading

Exchange publishes no catalog wire-format version on a release, so a release published by a **newer**
Epistola is only rejected when it is imported, with a message saying to upgrade this installation.
Catalogs subscribed from a manifest URL do not have this limitation — their manifest carries the
version, so the list shows "out of sync" or "upgrade Epistola" without a download. See
[ADR 0020](adr/0020-catalog-upstream-release-discovery.md).

## Permissions

Installing requires **both** `CATALOG_MANAGE` and `TEMPLATE_EDIT`.

`CATALOG_MANAGE` is the catalog-lifecycle permission — the same one that registers, upgrades and
removes a catalog. The nested ZIP import carries `TEMPLATE_EDIT`, and authorization is enforced on
every dispatch, so the effective requirement is the pair. This is not new (applying any subscribed
upgrade has always needed both), but the two are not held by the same role: `TENANT_ADMINISTRATOR`
has the first without the second, and `CONTENT_AUTHOR` the reverse. The UI checks both, so a reader
is told before a release is downloaded rather than after.

Browsing needs only `CATALOG_VIEW`.

## Surfaces

Installing is a **web UI** capability in this delivery. The REST API and MCP server are unchanged —
the same scoping decision publication made, and for the same reason: the flows involve a browser
redirect for enrollment and a deliberate human choice of release. Revisit if a non-interactive
installation path is actually wanted.

## What is not here

- **No resource-level diff before an upgrade.** A URL-subscribed catalog gets one, built by walking
  the source manifest; an Exchange release is an archive, so its dialog shows release metadata and
  states the abort guarantee instead. A real diff would mean downloading before previewing.
- **No automatic upgrading.** Deliberate — see ADR 0020.
- **No usage reporting to Exchange**, and therefore no use of its bulk upgrades feed. ADR 0020
  explains the trade.
