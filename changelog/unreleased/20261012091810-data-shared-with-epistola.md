---
type: feat
scopes: [version-check]
issues: [1063]
title: What an installation shares with Epistola is now defined and switchable, with a support badge on every page.
---

By default an installation shares its installation id and version; `epistola.sharing.*` (Helm `sharing`) turns that off. The version check is now anonymous, and it shows a security notice when a published advisory applies to the running version. A daily installation check feeds a support badge in the footer of every page. The badge cannot be hidden, and a failure or a choice not to share never reads as "not supported". **Shared with Epistola** (`/platform/sharing`) shows exactly what is sent. The application now refuses to start when `epistola.version-check.well-known-url` is set to something other than an absolute `http(s)` URL while the check is enabled; such a value never worked. See [Data shared with Epistola](docs/outbound-traffic.md).
