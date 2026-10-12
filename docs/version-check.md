# Version check

Epistola runs a default-on, installation-wide daily check for newer suite releases, for whether the
running version is still supported, and for published security advisories that apply to it. It
reads a single public JSON document, anonymously, and surfaces the result as notices on tenant-home
pages. What every outbound call sends is in [Data shared with Epistola](outbound-traffic.md).

- **Source:** `epistola.version-check.well-known-url` (Helm `versionCheck.wellKnownUrl`), default
  `https://epistola.app/.well-known/epistola/releases.json`. While the check is enabled, the
  application refuses to start unless it is an absolute `http(s)` URL.
- **Schedule:** a clustered `SINGLE_OWNER` task at a stable per-installation minute inside an
  08:00–08:59 UTC window (the minute is derived from the installation id, so each install keeps a
  fixed time while load is spread across the hour). Configurable via `daily-window-start-hour` /
  `daily-window-minutes`.
- **Anonymous:** the request carries no installation id and no version, and the `User-Agent` is
  plain `epistola-suite`. The document is the same for every installation and is evaluated locally.
  (Before 1.4 the check sent the version and installation id; counting installations is now the
  [installation check](outbound-traffic.md#installation-check)'s job, and only when the installation
  id is shared.)
- **Disable:** `epistola.version-check.enabled=false` (Helm `versionCheck.enabled: false`). It is
  off in the `local` profile, so development runs are not counted as installations.
- **Independent** of the commercial support-hub discovery (`hub.json`), which is a separate document.
  [ADR 0030](adr/0030-product-domain-and-discovery.md) proposes one discovery document and one
  `endpoint` setting for every Epistola-operated integration.

The check result is cached in global `app_metadata` (`version-check.status`); every instance reads
the cached value, and the scheduled task refreshes it.

## Where it lives

The feature is a standalone OSS module, `modules/epistola-version-check` (depends on `epistola-core`
and `epistola-web`, **not** the commercial support tier). It renders its tenant-home banner through
the `HomeNoticeContributor` SPI in `epistola-web` (`app.epistola.suite.htmx.home`), collected per
request by `HomeNoticeResolver` — the same contribution pattern as `FooterContributor`. The host app
only depends on the module for classpath presence; `TenantHandler` resolves notices generically and
never references the version-check service.

## `releases.json` contract

This repository only **consumes** the document — nothing here publishes it. The external
`epistola.app` site owns authoring and hosting it. Keep it in step with releases: `stable.version`
is the newest final tag (`vX.Y.Z`), `prerelease.version` the newest release-candidate tag
(`vX.Y.Z-RCn`). The `support` block is a **policy decision**, authored by hand — it is not derivable
from tags.

```json
{
  "schemaVersion": 1,
  "products": {
    "epistola-suite": {
      "stable": {
        "version": "1.1.0",
        "releaseUrl": "https://epistola.app/releases/epistola-suite/1.1.0",
        "changelogUrl": "https://epistola.app/changelog",
        "publishedAt": "2026-08-01T00:00:00Z"
      },
      "prerelease": {
        "version": "1.2.0-RC1",
        "releaseUrl": "https://epistola.app/releases/epistola-suite/1.2.0-RC1",
        "changelogUrl": "https://epistola.app/changelog"
      },
      "support": {
        "minVersion": "1.0.0",
        "until": "2027-01-31"
      },
      "advisories": [
        {
          "id": "EPIS-2026-001",
          "aliases": ["GHSA-69jx-3ffj-pjw9"],
          "severity": "medium",
          "summary": "Stored HTML injection in editor resource pickers",
          "published": "2026-06-25T16:18:42Z",
          "affected": [{ "introduced": "0.0.0", "lastAffected": "1.0.1" }],
          "fixedIn": null,
          "url": "https://github.com/epistola-app/epistola-suite/blob/main/vulnerabilities/2026-06-25-epis-2026-001.md"
        }
      ]
    }
  }
}
```

- **Stable vs pre-release build:** there is no "channel" the installation subscribes to — the
  operator chooses which build to deploy (the Helm image tag). A final build tracks the latest
  `stable`; a build that is itself a SemVer pre-release (RC/beta) tracks the latest `prerelease` when
  one is published (so RC testers hear about a newer RC) **and** always references the latest
  `stable`. SemVer precedence applies (a final release outranks its own release candidates).
- **`support.minVersion`:** installations strictly below this are flagged **unsupported** (a distinct
  banner urging an upgrade) instead of the ordinary "update available" banner. Omit the whole
  `support` block to disable support flagging.
- **`support.until`:** ISO date (`YYYY-MM-DD`) through which the floor is supported. A still-supported
  install whose `until` falls within `epistola.version-check.deprecation-warning-window` (default
  `90d`) of the check date gets an amber "support ending" banner to plan an upgrade; outside that
  window nothing extra shows. Support status itself (supported vs not) is decided purely by version
  comparison against `minVersion`, not by this date.

- **`advisories`:** the published security advisories (from `vulnerabilities/` in this repository).
  `affected` holds SemVer ranges: from `introduced` (inclusive) to `fixed` (exclusive) or
  `lastAffected` (inclusive), open-ended when neither is set. The running version is affected when
  it falls in any range; every applicable advisory is listed in a red security notice above the
  other banners, most severe first, with its fix release when there is one. An absent `advisories`
  means unknown (no notice); an empty list means none apply. Entries with a malformed id or range
  are skipped, text is cleaned and capped, and only `https:` links are rendered — the same holds
  for the release and changelog links.

Effective banner precedence on the tenant home page: **unsupported** (below `minVersion`, red) →
**support ending** (supported, `until` within the window, amber) → **pre-release** (supported
pre-release build, blue — acknowledges the build, notes a newer pre-release if any, and links the
latest stable) → **update available** (supported final build, newer stable, terracotta).

### Release candidates

A build that is itself a release candidate (`1.0.0-RC3`; the local `-SNAPSHOT` suffix is stripped
first) gets the blue **pre-release** banner: it acknowledges the running build, points at a newer
pre-release when one exists, and references the latest stable so the operator can move to GA when
ready. Support is compared with **strict SemVer**, where a pre-release ranks **below** its final —
so `1.0.0-RC3` is _below_ a `1.0.0` floor and is flagged unsupported (red) once GA is the minimum
supported version; in that case the banner's Release/Changelog links point at the **stable** release
that clears the floor.

All fields are optional and unknown fields are ignored, so the document can evolve without breaking
older installations. A missing document (404), an unparseable body, or a version that cannot be
compared degrades gracefully to "no banner"; the tenant-home page always renders.
