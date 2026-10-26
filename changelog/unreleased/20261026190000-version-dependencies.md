---
type: refactor
scopes: [catalog]
audience: dev
title: A version's dependencies and a release's pins are one table.
---

What a draft declares and what a release pins are rows of one table, by the version that declares
them (ADR 0036, step (c)). A declaration on another catalog's draft keeps naming that version when it
is released, now pinned to it. Nothing changes for authors.
