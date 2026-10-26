---
type: refactor
scopes: [catalog]
audience: dev
title: Releasing a draft keeps its row; every catalog version is one row.
---

A catalog's drafts and releases are rows of one table (ADR 0036). Releasing a draft freezes its row,
so the release is the draft it was made from, and the next version starts as a new row with what the
release holds. Nothing changes for authors.
