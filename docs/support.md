# Epistola support

> **Status:** Alpha (pilot). The support service and its protocol may still change; see
> [ADR 0050](adr/0050-support-connection.md).

How an installation connects to Epistola support, and what an installation administrator decides
about what goes there. For the wire protocol see
[Support service protocol](support-protocol.md); for every outbound call at a glance, see
[Data shared with Epistola](outbound-traffic.md).

## What it is

An installation with a support contract connects to the Epistola support service. Epistola then
grants it **services**, each an OAuth scope:

| Service          | Scope       | What goes to Epistola                                          |
| ---------------- | ----------- | -------------------------------------------------------------- |
| Telemetry        | `telemetry` | log lines and metrics of the installation                      |
| Support requests | `requests`  | support requests, with comments and status                     |
| Catalog mirror   | `mirror`    | released catalogs, for release checks                          |
| Usage            | `usage`     | documents generated and previews rendered, per tenant and hour |

The connection knows all four scopes. Telemetry and usage are described below, support requests in
[Support requests](support-requests.md), the catalog mirror in [Catalog mirror](catalog-mirror.md).

A service is used only when an installation administrator **selected** it and Epistola **granted**
it. Nothing goes to Epistola before the installation is registered, and an installation that is not
connected works exactly as without support.

## Turning it on

```yaml
support:
  enabled: true # EPISTOLA_SUPPORT_ENABLED
  installation:
    companyName: Example Hosting B.V. # who Epistola contacts about this installation
    adminEmail: beheer@example.org
    environment: production
  services: [telemetry, requests, usage] # the first selection; Platform → Support connections wins once saved
  autoRegister: false # true: register on its own instead of waiting for an administrator
  endpoint: "" # empty: https://support.{product-domain}
```

The page is **Platform → Support connections** (`/platform/support`), for installation administrators.

Epistola support is a support connection like any other (ADR 0053), named by its endpoint. With
`support.enabled` the installation stores it at startup when it is not stored, at the first start and
again after an administrator removed it; one that exists is left as it is. Changing `support.endpoint`
stores a connection at the new endpoint beside the old one, which an administrator removes. Its privacy
is the configured default (below) until an administrator saves settings of its own for it.

## Applying for support

1. **Register.** The administrator checks the company name and email address (Epistola contacts that
   address), corrects them if needed, and presses **Register**. The installation sends its id, its
   public key, its version and the contact; nothing else.
2. **Waiting.** The page says "Waiting for Epistola" and shows a claim code, only as a reference.
   Epistola contacts the administrator, then activates the installation with the services agreed, or
   declines it. A registration nobody activates expires after 7 days; the page then offers to register
   again.
3. **Active.** The services Epistola granted appear under **Services**, each as selected, granted, in
   use, or paused.

**Declined:** the page says so and offers **Register again**; the installation does not register on its
own after a decline, even with `autoRegister`.

**Paused:** Epistola can pause services for a while, with a reason and an end date, shown on the
platform page and the tenant's Support page. Nothing is sent for a paused service until the pause
ends.

## Privacy

Two settings decide what a support connection sees (ADR 0052 §1–2), per connection, on its page under
**Privacy**. Until an administrator saves them for a connection, it follows the configured default
(`epistola.support.privacy.tenant-identity` and `.telemetry`, Helm `support.privacy.tenantIdentity` and
`support.privacy.telemetry`). There is no installation-wide setting on the page.

**Tenants: confidential (the default) or named.**

- **Confidential:** every tenant goes to Epistola as a **reference**, `t-` and 16 hex digits, an HMAC
  of the tenant key under a secret the installation generates once and never sends. Epistola can tell
  tenants apart and follow one over time, without knowing who it is. Each tenant's **Support** page
  shows its own reference, so the installation can tell Epistola who a reference is when it wants to.
  Every service that names a tenant sends this reference. The **catalog mirror is unavailable**: a
  release holds the templates themselves, which name the organisation. Making tenants confidential
  withdraws everything the mirror holds.
- **Named:** tenant keys go as they are.

**Telemetry: pseudonymised (the default) or everything.**

- **Pseudonymised:** a log line goes as its message as written in the code, without the values filled
  in (`"Render failed for {}"`); an error as its type and stack frames, without its message; no thread
  names; library lines only at WARN and above, and without their message, which a library may have
  built with values (their logger, level and stack frames still go). A guard (`LogMessageTemplateTest`)
  keeps values out of the suite's own log messages.
- **Everything:** log lines and errors as written, which can name people and organisations, also
  while tenants are confidential.

## What each tenant shares

A service that sends something per tenant is set per tenant, with a setting for the whole
installation under **Setup for every tenant** (ADR 0052 §5):

| Setting         | Requests, mirror | Usage | The tenant                                   |
| --------------- | ---------------- | ----- | -------------------------------------------- |
| Ask each tenant | yes              | no    | sets it up in its setup wizard               |
| On by default   | yes              | yes   | follows it, or changes it for itself         |
| Off by default  | yes              | yes   | follows it, or changes it for itself         |
| Required        | no               | yes   | shares it, without a choice; not shown to it |
| Not allowed     | yes              | yes   | cannot share it; sees why and who decides    |

- **Defaults** are recorded as agreed on the tenants' behalf, so saving one that shares asks the
  administrator to agree. Helm can set the support request and mirror defaults until one is saved
  (`support.tenantDefaults`).
- **Required** is offered only for usage: support requests and the mirror send tenant content, which
  needs the tenant's own opt-in (ADR 0050 §10).
- **Not allowed** withdraws every tenant's choice at once, its own choice included, and stops what
  waits to be sent. Allowed again, tenants follow whatever default is set next, or are asked.

A tenant administrator (with `INTEGRATIONS_MANAGE`) sets it on the tenant's **Support** page: a setup
wizard while nothing is set up (one step per service, then agreeing on the tenant's behalf), and
afterwards what is shared, by whom it was decided, and **Change**. A service the installation has
not selected is not offered; with nothing to choose, the page says so. Disconnecting forgets every
tenant's choices and the setup for every tenant, Required and Not allowed included.

## Usage

For installations Epistola bills by use, such as a partner hosting its clients (ADR 0052 §6).

**What is counted.** The suite's own [generation statistics](generation-statistics.md), which are
there whether or not usage is shared: documents generated (what Epistola bills) and previews rendered
(counted, not billed), per tenant and hour, rolled up in the background at no cost to generating. A
document deleted right after it was generated still counts.

**When it is sent.** Once an hour, at a moment within the first ten minutes past the hour, chosen at
random once per installation and kept (`app_metadata`, `support.usage.report-moment`), for the hour
before. It waits until the statistics hold that whole hour, a few minutes at most. Hours missed while
the installation or the support service was down are sent when both are back, up to 72 of them
(`epistola.support.usage.backlog`, Helm `support.usage.backlog`, at least an hour, and never further
back than the statistics keep hours). Which hours went out (`support_usage_reports`) is kept only as
long as a round can look back, plus a day. One
node sends at a time, never one whose clock is off.

**What is sent.** For every tenant that shares usage and has used anything: the hour's figures and its
**running totals** since the installation started counting, under its key or reference. While no
tenant shares, the report is empty: a tenant missing from a report reads as having stopped.
Hours while usage was not granted or not allowed are never sent, also not once it is again. Epistola bills
from what the totals grew by, so a report that never arrives loses nothing: the next one carries it.

**Turning it on.**

1. Epistola grants `usage`.
2. Select **Usage** under Services.
3. Under **Setup for every tenant**, set usage to Required, or a default that tenants may change.

The first report is each tenant's starting point; billing starts with the growth after it.

## Telemetry

Telemetry is a service like any other (ADR 0053): log lines at `epistola.support.telemetry.log-level`
(INFO) and above, and the metrics, go over OTLP to every support connection that has `telemetry`
selected and granted, each through its own queue and shaped by that connection's privacy settings, so
one that is slow or withdraws holds back no other. Epistola support's OTLP endpoint comes from its
metadata; another connection's is `{endpoint}/otlp`. While no connection is selected for telemetry the
installation aggregates nothing for it.
Every resource names the installation; the support service refuses a report that names another one
than its token's. Metrics never carry the `template` tag (a template id is the tenant's content); a
tenant tag, when not stripped, names a confidential tenant by its reference. Logs and metrics can be
switched off separately (`epistola.support.telemetry.logs`,
`.metrics`), or both with Helm `support.telemetry.enabled`.

## Other support connections

Besides Epistola support, the installation can share with further parties, such as a partner that
hosts the suite for its clients or supports a client who hosts it (ADR 0053). Each is a **support
connection** of its own. Each signs in by its `auth` alone: with a token that party issued
(`BEARER_TOKEN`, no registration and no claim code), or by registering with the installation's own key
at its endpoint, as with Epistola support (`PRIVATE_KEY_JWT`, for a party that runs the support service
itself). A connection that registers is checked like the others but registers only when an
administrator asks (`RegisterSupportConnection` with its id), and gets its own claim code, tokens and
disconnect.
They come from the configuration, independent of `epistola.support.enabled`, so an installation can
connect to its partner alone:

```yaml
epistola:
  support:
    connections:
      - name: Acme Partner
        endpoint: https://desk.partner.example/epistola
        token: ${ACME_SUPPORT_TOKEN}
        services: [requests, usage]
        tenant-identity: named # its own privacy; left out: epistola.support.privacy
        tenant-defaults:
          requests: share # what tenants share with it until they choose
      - name: Hosting Partner
        endpoint: https://support.hosting.example
        auth: register # registers with this installation's own key; no token
        services: [requests, telemetry]
    requests:
      handled-by: https://support.hosting.example # or inbox (the default)
```

In Helm, `support.connections` takes the same entries (`auth`, `tenantIdentity`, `telemetry`,
`tenantDefaults`, `escalatesTo`) with `tokenSecret: { name, key }` instead of the token, and
`support.requests.handledBy` names who handles new support requests: `inbox`, or a connection's
endpoint. `tenant-defaults` is applied at startup once per configured value, as
`epistola.support.tenant-defaults` is for Epistola support's connection.

An installation administrator can also add one under Platform → Support connections with **Add
connection**: **Look up** a domain to fill in what it publishes in its `/.well-known/epistola.json` (a
`support` object with `name`, `endpoint`, `services`, and optionally `tenantIdentity` and `telemetry`),
or fill in the endpoint by hand, and enter the token the other side issued or choose to register. Each
connection has a page of its own, where it can be renamed, have its services, token, privacy and setup
for every tenant changed, and be removed: requests it handled return to the support desk's inbox. A connection an administrator added or saved is left alone by the configuration. The endpoint names the connection: the same endpoint at the next start is the same
connection, with the name and token from the configuration. An entry removed from the configuration
leaves the connection in the installation.

The suite checks each connection every `epistola.support.check-interval` with
`GET {endpoint}/api/v1/connection`: the answer says whether it is active and which services it grants.
A service is used over a connection when it is listed in `services` and granted. A `401` reads as a
refused token; an unreachable connection keeps its services but uses none until a check reaches it
again. Platform → Support connections lists every connection with its state, the services granted and
those used.

**Privacy per connection.** Each connection has its own privacy: whether tenants are named or
confidential to it, and how much telemetry carries over it. That is what an administrator saves on its
page, else its configuration entry's `tenant-identity` and `telemetry` (Helm `tenantIdentity`,
`telemetry`), else the configured default (above), Epistola support's included. A confidential tenant has one reference for every connection, so
Epistola and a partner can talk about it by the same reference.

**Sharing per connection.** What a tenant shares is set per connection too: its consent for support
requests, the installation's preset and the restriction (Not allowed, Required). A tenant's Support
page has a "Sharing with …" panel for every other connection that offers something, next to the setup
for Epistola support; Platform → Support connections has the preset for every tenant per connection. One
connection's choice never changes another's.

**Support requests over a partner.** Who handles new support requests (Platform → Support connections)
can be any connection the installation selected support requests for, and the support desk can pass a
request on to any of them. A request goes to such a connection while the tenant shares support
requests with it; when it is handed over, everything it holds so far goes along, except internal notes.
Afterwards a reply or screenshot goes there when it is written while the tenant still shares with that
connection: what is written while it does not never goes there, also once it shares again. Replies,
status and priority come back from every connection the suite can reach, each with its own position in
its changes feed; only the connection that handles a request sets its status and priority.

**Attaching a connection.** On a request's page the support desk can attach a further connection
besides the one (or the inbox) that handles it, such as a partner consulting Epistola support. An
attached connection gets the request and everything written on it except internal notes, shaped by its
own privacy; its replies appear in the request and go on to the other parties; it does not set status
or priority, but hears every change of them. It can be attached as a request can be passed on to it (the
tenant shares with it), and detached: nothing more goes there, and what it already has stays.

**Usage over a partner.** Usage is reported to every connection the installation selected `usage` for,
each on its own: the hours it has had, its back-off, where its reporting started and its lock are its
own, and each tenant is named as that connection's privacy says. Whether tenants share usage with a
connection is set per connection, as every service is (above); Epistola support's reporting is as before.

## Disconnecting

**Disconnect**, under the danger zone on the platform page, ends the registration at Epistola: its
tokens stop working, and the installation forgets its key, so it does not register again by itself.
When Epistola cannot be reached, the page offers to remove the connection here only.

## Where to look

| Who                        | Page                           | What                                     |
| -------------------------- | ------------------------------ | ---------------------------------------- |
| Installation administrator | Platform → Support connections | connection, claim code, services         |
| Tenant administrator       | Tenant → Support               | whether it is connected, services in use |
