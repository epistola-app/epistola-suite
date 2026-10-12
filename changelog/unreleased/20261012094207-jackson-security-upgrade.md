---
type: fix
scopes: [security, deps]
title: Jackson is upgraded to 3.1.7 and 2.21.7 to pick up seven upstream security fixes.
---

The Spring Boot BOM pinned Jackson 3.1.5 and 2.21.5, which carry parser and databind
denial-of-service and `Path` deserialization CVEs. All three applications now override the BOM to
the patched releases. See `EPIS-DEP-2026-002` in `VULNERABILITIES.md`.
