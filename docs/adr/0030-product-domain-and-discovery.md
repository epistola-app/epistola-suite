# ADR 0030: One product domain, one discovery document

- **Status:** Proposed
- **Date:** 2026-10-12
- **Deciders:** Epistola team
- **Tags:** configuration, outbound-traffic, discovery, version-check, exchange, support

## Context

A suite installation calls epistola.app for four things, and each one finds its endpoint its own
way:

| Integration        | Setting today                                    | What it points at                                                        |
| ------------------ | ------------------------------------------------ | ------------------------------------------------------------------------ |
| Release check      | `epistola.version-check.well-known-url`          | the data itself, at `/.well-known/epistola/releases.json`                |
| Installation check | `epistola.installation-check.url`                | a URL template, `/installation/{id}.json`                                |
| Exchange           | `epistola.exchange.discovery-url`, or `base-url` | a discovery document, `exchange.json`, naming `issuer` and `baseUrl`     |
| Support hub        | `epistola.support.hub.discovery-url`, or `host`  | a discovery document, `hub.json`, default kept in the hub-client library |

That costs three things.

- **No single switch.** A staging suite talking to a staging epistola.app, or an installation
  behind a proxy, needs four overrides with three naming styles (`well-known-url`, `discovery-url`,
  `url`, plus `base-url` and `host` to skip discovery).
- **Endpoints are frozen into releases.** The release and installation checks have their paths
  compiled in. Installations run one version for years, so epistola.app cannot move either without
  serving the old path indefinitely. Exchange and the hub can move, through their own documents.
- **`.well-known` is used for data.** RFC 8615 reserves `/.well-known/` for metadata a client finds
  without prior knowledge of a site — OIDC's `openid-configuration` is the model: one small
  document naming where everything else lives. `releases.json` is product data that happens to sit
  there.

## Considered options

1. **Keep it as it is.** No work, and all three costs stay.
2. **One base setting, defaults derived from it.** `epistola.product-domain` feeds every default
   path. One switch for staging and proxies, but endpoints stay frozen into releases.
3. **One base setting plus one discovery document.** `https://{product-domain}/.well-known/epistola.json`
   names every endpoint; the suite caches it and falls back to built-in defaults.
4. **Move `releases.json` out of `.well-known` on its own.** Released installations keep calling
   the old path, so epistola.app serves both forever and nothing is gained.

## Decision

**Option 3.**

### The product domain

`epistola.product-domain` (Helm `productDomain`), default `epistola.app`: a host name, optionally
with a port. Everything Epistola-operated is reached over `https://` on it or through the document
it serves. It is a domain rather than a URL so it cannot be set to plain `http:`; local development
and tests that point at a plain-`http` mock set the per-integration endpoint instead.

The support badge's links (the installation page and the `/help/` pages) are built from it as well,
so a staging suite links to the staging site.

### The discovery document

`GET https://{product-domain}/.well-known/epistola.json`:

```json
{
  "schemaVersion": 1,
  "versionCheck": { "endpoint": "https://epistola.app/releases.json" },
  "installationCheck": { "endpoint": "https://epistola.app/installation/{id}.json" },
  "exchange": {
    "issuer": "https://exchange.epistola.app",
    "endpoint": "https://exchange.epistola.app"
  },
  "supportHub": { "endpoint": "hub.epistola.app:443" }
}
```

- **Anonymous.** No installation id, no version, `User-Agent: epistola-suite`. It gets its own row
  in the outbound-traffic documentation.
- **Fetched only when something needs it.** When the version check, the installation check,
  Exchange and the support hub are all off or all have an explicit endpoint, it is never fetched;
  the no-data configuration stays at zero outbound calls.
- **Cached and shared.** Refreshed once a day in the existing check window by a clustered
  `SINGLE_OWNER` task, stored in `app_metadata`, read by every node. A failed refresh keeps the last
  good copy. A component that needs an endpoint before any copy exists — the hub at first start —
  fetches once on demand.
- **Never a new failure.** Unreachable, malformed, or an unknown `schemaVersion`: the suite uses
  built-in defaults, which are today's paths on the product domain. A failed discovery is therefore
  never worse than the current behaviour, and the checks stay independent of each other.
- **Stricter than operator configuration.** A discovered endpoint must be `https:`, on the product
  domain or one of its subdomains, and the installation-check template must contain `{id}`. Anything
  else is ignored in favour of the built-in default for that integration, and logged. Operator
  overrides are trusted as configured.
- **A stable wire format.** Version 1 only gains optional fields. Removing or changing one is
  `schemaVersion: 2`, served alongside 1 for as long as released suites read it.

### Resolution

For each integration, the first of these wins:

1. its explicit `endpoint` setting;
2. the cached discovery document;
3. the built-in default on the product domain.

### Settings: one shape for every integration

Each integration gets one `endpoint` setting. Blank means "discover it".

| Before                                         | After                                              |
| ---------------------------------------------- | -------------------------------------------------- |
| —                                              | `epistola.product-domain` (default `epistola.app`) |
| `epistola.version-check.well-known-url`        | `epistola.version-check.endpoint`                  |
| `epistola.installation-check.url` (unreleased) | `epistola.installation-check.endpoint`             |
| `epistola.exchange.discovery-url`              | removed — discovery follows the product domain     |
| `epistola.exchange.base-url`                   | `epistola.exchange.endpoint`                       |
| `epistola.support.hub.discovery-url`           | removed — discovery follows the product domain     |
| `epistola.support.hub.host` + `port`           | `epistola.support.hub.endpoint` (`host:port`)      |

`enabled`, `exchange.callback-url`, `support.hub.plaintext` and `support.hub.node-id` keep their
names. Helm follows the same shape: `productDomain`, `versionCheck.endpoint`,
`installationCheck.endpoint`, `exchange.endpoint`, `support.hub.endpoint`, each rendered only when
set.

The renames are deliberately not bridged: the defaults are what installations use, and a setting
that is still present under an old name fails startup with a message naming its replacement rather
than being ignored.

## Consequences

- **A breaking configuration change.** Renamed and removed GA settings need a major release under
  the stability contract, so this ships in 2.0. The CHANGELOG lists every rename, and the chart
  changelog every Helm key.
- **epistola.app serves a new document and keeps the old ones.** `.well-known/epistola.json` is new;
  `/.well-known/epistola/releases.json`, `exchange.json` and `hub.json` stay for released suites.
  The hub's discovery document currently returns 404 there, and needs fixing either way.
- **The hub default moves into the suite.** The hub-client library's built-in discovery URL is no
  longer consulted; the suite passes it the resolved endpoint.
- **New code with failure paths to test:** the discovery client and cache, each fallback
  (unreachable, malformed, unknown schema version, rejected endpoint), the startup check for old
  setting names, and the resolution order per integration.
- **epistola.app can move any endpoint without a suite release** — including the two checks — and
  has one place to add a later integration, or to switch one off for everyone.

## References

- [Version check](../version-check.md)
- [RFC 8615: Well-Known Uniform Resource Identifiers](https://www.rfc-editor.org/rfc/rfc8615)
- [OpenID Connect Discovery](https://openid.net/specs/openid-connect-discovery-1_0.html), the model
  for a single discovery document
- PR #1067 — installation check and support badge, which add the second check and the badge links
