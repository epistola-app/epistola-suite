---
type: docs
scopes: [agents]
audience: dev
title: Four replacement skills, written from the code they describe.
---

The six skills removed earlier taught patterns the build rejects, and deleting them left the CQRS and contract areas with pointers but no guide. `command-query`, `ui-page`, `tests` and `contract-bump` replace them in the shape the review argues for: each names the exemplar file to copy, the rules that are not obvious from it, and the guard tests that fail when they are broken — rather than embedding snippets that rot. Every path and symbol they cite was checked to exist. `docs/htmx.md` loses the `TenantId.of` call that never existed, which was the one place a reader could still be misled the way the old skill did; the real idiom is `request.tenantId()`.
