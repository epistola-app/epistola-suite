---
type: feat
scopes: [exchange]
audience: dev
title: Added metrics for catalog publication.
---

`epistola.exchange.publication.submissions` and `epistola.exchange.credential.refresh` count what each node did; `epistola.installation.exchange_publications`, `epistola.installation.exchange_connections` and `epistola.installation.exchange_publication_oldest_active_age_seconds` describe the installation and are published once per installation by an advisory-lock elected replica. Alert on the queue age — it is the reliable signal that publication has stopped progressing. See `docs/metrics.md`.
