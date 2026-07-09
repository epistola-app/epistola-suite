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

- **Channel selection:** a running build that is itself a SemVer pre-release (an RC) compares against
  `prerelease` when it is present, otherwise `stable`; a final build always compares against
  `stable`. SemVer precedence applies (a final release outranks its own release candidates).
- **`support.minVersion`:** installations strictly below this are flagged **unsupported** (a distinct
  banner urging an upgrade) instead of the ordinary "update available" banner. Omit the whole
  `support` block to disable support flagging.
- **`support.until`:** informational ISO date (`YYYY-MM-DD`) through which the floor is supported;
  shown to operators so they can plan. Support status itself is decided purely by version
  comparison, not by this date.

All fields are optional and unknown fields are ignored, so the document can evolve without breaking
older installations. A missing document (404), an unparseable body, or a version that cannot be
compared degrades gracefully to "no banner"; the tenant-home page always renders.
