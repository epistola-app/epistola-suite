---
type: refactor
scopes: [environments]
audience: dev
title: A deployment names the version it serves.
---

An environment's deployment names one catalog version, a release or a draft, instead of a release
number or a draft (ADR 0036, step (d)). A deployed draft stays deployed as the release it becomes,
logged as released, as before. Nothing changes for authors.
