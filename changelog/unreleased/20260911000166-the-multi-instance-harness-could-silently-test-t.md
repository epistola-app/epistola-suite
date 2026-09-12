---
type: fix
scopes: [test]
audience: dev
title: The multi-instance harness could silently test the previous build.
---

`boot_jar()` picked the alphabetically first jar, so a leftover `epistola-1.0.0-RC4.jar` won over today's `epistola-dev.jar` — the harness passed while exercising a months-old build and schema. It now takes the newest by modification time. `MIT_JAR_DIR` and `MIT_PROFILES` are also overridable, since the demo distribution became a separate app and is the one that seeds the tenant and API key load tooling needs.
