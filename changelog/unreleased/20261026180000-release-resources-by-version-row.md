---
type: refactor
scopes: [catalog]
audience: dev
title: A release's resources are stored under its version row.
---

A release's resources name the version row it is (ADR 0036, step (b)), as a draft's do, so a draft
and a release are read through one query. Releasing turns the draft's resources into the release's
instead of writing a copy. Nothing changes for authors.
