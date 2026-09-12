---
type: test
scopes: [architecture]
audience: dev
title: The catalog domain is held apart from Exchange by a test.
---

ADR 0018 and CLAUDE.md both say the catalog domain must not reference the Exchange integration, and nothing enforced it. ArchUnit checks bytecode rather than imports, so a fully qualified reference — which carries no import line for a grep to find — fails the build too.
