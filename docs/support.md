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

The connection knows all four scopes. Telemetry is described below; the others are described with
the service itself as it lands.

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
  Every service that names a tenant sends this reference.
- **Named:** tenant keys go as they are.

**Telemetry: pseudonymised (the default) or everything.**

- **Pseudonymised:** a log line goes as its message as written in the code, without the values filled
  in (`"Render failed for {}"`); an error as its type and stack frames, without its message; no thread
  names; library lines only at WARN and above, and without their message, which a library may have
  built with values (their logger, level and stack frames still go). A guard (`LogMessageTemplateTest`)
  keeps values out of the suite's own log messages.
- **Everything:** log lines and errors as written, which can name people and organisations, also
  while tenants are confidential.

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
