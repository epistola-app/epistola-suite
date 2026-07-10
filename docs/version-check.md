# Version check

Epistola runs a default-on, installation-wide daily check for newer suite releases and, optionally,
for whether the running version is still supported. It reads a single public JSON document and
surfaces the result as a banner on tenant-home pages.

- **Source:** `epistola.version-check.well-known-url`, default
  `https://epistola.app/.well-known/epistola/releases.json`.
- **Schedule:** a clustered `SINGLE_OWNER` task at a stable per-installation minute inside an
  08:00–08:59 UTC window (the minute is derived from the installation id, so each install keeps a
  fixed time while load is spread across the hour). Configurable via `daily-window-start-hour` /
  `daily-window-minutes`.
- **Privacy:** only the current version string is sent (`User-Agent` and `X-Epistola-Suite-Version`
  headers). No installation id, tenant data, or other identifiers leave the instance.
- **Disable:** `epistola.version-check.enabled=false`.
- **Independent** of the commercial support-hub discovery (`hub.json`), which is a separate document.

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
      }
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
