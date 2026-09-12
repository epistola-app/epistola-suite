---
type: feat
scopes: [catalogs]
audience: user
title: Reorganising catalogs has its own page.
---

Relocation moves out of the resource graph — a read-only diagnostic tool an author reorganising catalogs would not think to open, and which could only act on one node — and onto `/catalogs/organise`: a browser across catalogs that lets you select resources, choose where each goes, preview the impact, and apply it as one batch. Deep-linkable via `?resource=<type>:<catalog>/<key>`, so anything that notices a misplaced resource can hand off with it selected; the graph now links here instead of hosting the move. Relocation no longer requires the `resource-graph` toggle.
