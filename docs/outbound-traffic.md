# Data shared with Epistola, and outbound traffic

Every network call an Epistola Suite installation can make on its own, what it sends, and how to
turn it off. Hand this page to a security officer or a procurement team: it is meant to answer
"what leaves our installation?" in one place.

## Data shared with Epistola (v1)

An installation shares only what is on this list with Epistola. The Suite sends nothing outside it,
and epistola.app stores nothing outside it.

| Data                        | Setting (Helm)                                                  | Default | Used for                                               |
| --------------------------- | --------------------------------------------------------------- | ------- | ------------------------------------------------------ |
| Installation id (UUIDv7)    | `epistola.sharing.installation-id` (`sharing.installationId`)   | on      | support status, version policy, counting installations |
| Suite version               | `epistola.sharing.suite-version` (`sharing.suiteVersion`)       | on      | version statistics                                     |
| Usage statistics (counters) | `epistola.sharing.usage-statistics` (`sharing.usageStatistics`) | on      | **reserved: nothing is sent yet**                      |

- **Everything is shared by default.** Turn off what you do not want to share.
- **Implicit in every request:** the address it comes from and the time. Epistola uses the address
  only to look up the country, and does not store it. Epistola keeps its daily records for 13
  months ([privacy statement](https://epistola.app/en/privacy)).
- **Never shared:** hostnames, tenant names or ids, user data, document or template contents,
  configuration values, exact counts.
- **The installation id** is a random UUIDv7 created by the first database migration
  (`app_metadata`, key `installation`). It identifies the installation, not an organisation or a
  person.
- **Usage statistics** exist as a setting so the configuration is stable, but nothing is sent. The
  release that introduces the counters adds them to this list (v2) and announces prominently that
  installations on the default will start sending them.

Platform tenant managers see the current settings, exactly what each check sends and when it last
ran on **Shared with Epistola** (`/platform/sharing`, linked from the tenant list). The page can also
run the checks immediately, for example after opening the firewall.

## Calls to epistola.app

Both run once a day, on one node of the cluster, at a stable minute in the 08:00–08:59 UTC window
derived from the installation id; never at startup. Timeouts are 3 s to connect and 5 s to read;
a failure waits for the next day.

### Version check

|          |                                                                                                           |
| -------- | --------------------------------------------------------------------------------------------------------- |
| Request  | `GET https://epistola.app/.well-known/epistola/releases.json`                                             |
| Sends    | **Nothing that identifies the installation**: `Accept: application/json`, `User-Agent: epistola-suite`    |
| Returns  | releases, the support policy and the published security advisories, the same for everyone                 |
| Shows    | update, support and security-advisory notices on the tenant home page ([version check](version-check.md)) |
| Turn off | `epistola.version-check.enabled=false` (Helm `versionCheck.enabled: false`)                               |

The Suite matches the advisories against its own version locally, so security notices never depend
on sharing anything. Keep it on.

### Installation check

|          |                                                                                                                        |
| -------- | ---------------------------------------------------------------------------------------------------------------------- |
| Request  | `GET https://epistola.app/installation/{installation id}.json`                                                         |
| Sends    | the installation id (in the path), `X-Epistola-Suite-Version` when the version is shared, `User-Agent: epistola-suite` |
| Returns  | whether the installation is registered, its support status, support party, end date and version policy                 |
| Shows    | the support badge (below)                                                                                              |
| Turn off | `epistola.sharing.installation-id=false` (Helm `sharing.installationId: false`)                                        |
| URL      | `epistola.installation-check.url` (Helm `installationCheck.url`), `{id}` replaced                                      |

The response is treated as untrusted input: it is read up to 64 KiB, only the documented fields are
used, text is cleaned and capped, and links are kept only when they are `https:`.

## The support badge

Every page of the signed-in UI shows a support badge in the footer. It is always there; no setting
hides it, because whether support can be verified should be visible to every user. Only its contents
depend on what the installation shares. It is never shown in generated documents.

| Situation                                                       | Badge                                                     | Links to                                              |
| --------------------------------------------------------------- | --------------------------------------------------------- | ----------------------------------------------------- |
| Registered, supported, version within the installation's policy | "Supported by {party} until {date}"                       | the installation page on epistola.app                 |
| Registered, supported, version below the policy                 | "Supported by {party}, but version {v} is out of support" | the installation page                                 |
| Registered, ended or revoked                                    | "Support ended" / "Not supported"                         | the installation page                                 |
| Installation id shared, not registered                          | "Not registered with Epistola"                            | the installation page                                 |
| Installation id shared, no answer in the last 3 days            | "Support could not be checked"                            | `https://epistola.app/help/support-check-unavailable` |
| Installation id not shared                                      | "Support cannot be verified"                              | `https://epistola.app/help/support-not-verifiable`    |

- The installation page link is `https://epistola.app/installation/{id}`, with `?version=` only when
  the version is shared. The `/help/` links carry no data.
- Every link uses `rel="noreferrer"`, so the installation's hostname never reaches epistola.app.
- A network failure or a choice not to share never reads as "not supported". The last good answer
  keeps showing for three days (`epistola.installation-check.stale-after`), so one failed day does
  not flip the badge.

## Sending no data to Epistola at all

Turn off the version check and every sharing setting; with Helm, use
[`charts/epistola/examples/no-data-to-epistola.yaml`](../charts/epistola/examples/no-data-to-epistola.yaml):

```yaml
sharing:
  installationId: false
  suiteVersion: false
  usageStatistics: false
versionCheck:
  enabled: false
```

The installation keeps working fully. The badge then permanently says "Support cannot be verified",
and there are no release or security-advisory notices, so follow releases another way. Keep the
support hub and Exchange (below) off as well; they are off by default.

## Other outbound calls

These are integrations an operator sets up explicitly, or calls to systems the operator configures.
They are not part of `epistola.sharing`.

| Call                          | Destination                                                      | Default                                | What it sends                                                                                                                                                                                                            | Turn off                                                                      |
| ----------------------------- | ---------------------------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------- |
| Support hub (commercial tier) | `epistola.app/.well-known/epistola/hub.json`, then the hub       | off                                    | registration (installation id, name, version, node hostname), entitlements, health pings; per feature: feedback (text, the user's email on comments, browser console log, screenshot), catalog snapshots, tenant backups | `epistola.support.enabled=false` (Helm `support.enabled`)                     |
| Hub telemetry                 | the hub                                                          | on **when the hub is on** and entitled | log messages at INFO and above, including stack traces, and all metrics, tagged with hostname and installation id                                                                                                        | `epistola.support.telemetry.enabled=false` (Helm `support.telemetry.enabled`) |
| Exchange                      | `epistola.app/.well-known/epistola/exchange.json`, then Exchange | off                                    | on enrolment: the tenant's name, the installation id and the public callback URL; published catalog archives                                                                                                             | `epistola.exchange.enabled=false` (Helm `exchange.enabled`)                   |
| Catalog update check          | the source URL of each subscribed catalog                        | on                                     | a manifest request, with the source's credentials when configured; every 6 hours per catalog                                                                                                                             | `epistola.catalog.upstream-check.enabled=false`                               |
| OIDC                          | your identity provider (`oidc.issuerUri`)                        | when configured                        | standard OpenID Connect discovery, token and key requests                                                                                                                                                                | —                                                                             |
| Own telemetry export          | your collector (`observability.otlpEndpoint`)                    | off                                    | metrics, traces and logs                                                                                                                                                                                                 | leave `observability.otlpEndpoint` empty                                      |

The browser loads nothing from third parties: the Content Security Policy only allows the
installation's own origin.
