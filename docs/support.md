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
  services: [telemetry, requests, usage] # the first selection; Platform → Epistola support wins once saved
  autoRegister: false # true: register on its own instead of waiting for an administrator
  endpoint: "" # empty: found through product discovery
```

The page is **Platform → Epistola support** (`/platform/support`), for installation administrators.

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

Two installation settings under **Privacy** decide what Epistola sees (ADR 0052 §1–2). Helm sets them
until an administrator saves them (`support.privacy.tenantIdentity`, `support.privacy.telemetry`).

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

With `telemetry` selected and granted, log lines at `epistola.support.telemetry.log-level` (INFO) and
above, and the metrics, go to the support service over OTLP, shaped by the privacy settings above.
Every resource names the installation; the support service refuses a report that names another one
than its token's. Metrics never carry the `template` tag (a template id is the tenant's content); a
tenant tag, when not stripped, names a confidential tenant by its reference. Logs and metrics can be
switched off separately (`epistola.support.telemetry.logs`,
`.metrics`), or both with Helm `support.telemetry.enabled`.

## Disconnecting

**Disconnect**, under the danger zone on the platform page, ends the registration at Epistola: its
tokens stop working, and the installation forgets its key, so it does not register again by itself.
When Epistola cannot be reached, the page offers to remove the connection here only.

## Where to look

| Who                        | Page                        | What                                     |
| -------------------------- | --------------------------- | ---------------------------------------- |
| Installation administrator | Platform → Epistola support | connection, claim code, services         |
| Tenant administrator       | Tenant → Support            | whether it is connected, services in use |
