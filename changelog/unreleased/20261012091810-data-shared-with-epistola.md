---
type: feat
scopes: [version-check]
issues: [1063]
title: What an installation shares with Epistola is defined and switchable, and every page shows a support badge.
---

An installation shares its installation id and version by default; `epistola.sharing.*` (Helm `sharing`) turns that off. The version check is anonymous and warns when a published security advisory applies to the running version. **Shared with Epistola** (`/platform/sharing`) shows exactly what is sent. See [Data shared with Epistola](docs/outbound-traffic.md).
