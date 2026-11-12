---
type: feat
scopes: [quality]
audience: user
breaking: true
maturity: alpha
title: Quality checks read templates for clear language, and findings belong to the catalog release they were checked in.
---

Built-in rules report repeated words, long sentences, spacing mistakes and formal words with a plain alternative, in Dutch and English. An optional LanguageTool server (`epistola.quality.language-tool.url`, switched on per tenant) adds spelling and grammar. Templates are checked a few seconds after each save. Each draft and release shows the findings checked against what it holds, and a finding about something changed since is marked outdated. The report is a catalog's page, at `…/catalogs/{namespace}/{key}/quality`; the tenant-wide report is gone. Upgrading keeps every note, comment and ignore. Requires the `quality` feature; see `docs/quality.md`.
