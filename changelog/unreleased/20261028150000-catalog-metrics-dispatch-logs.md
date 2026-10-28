---
type: feat
scopes: [observability, catalog]
title: Catalog releases and upstream checks are counted, and log lines name the command they were written for.
---

Two counters: `epistola.catalog.releases{via}` counts every release that comes to be in a tenant, by
how it arrived (cut here, uploaded, from a registry, the Exchange, a URL source, bundled, the 1.x
upgrade or a snapshot restore), and `epistola.catalog.upstream.checks{outcome}` counts each check of
a subscribed catalog's source by what it found. Releases cut, deployments and quality runs are read
from the existing command timers; `docs/metrics.md` says which.

Every command and query puts its name (`command` or `query`) and, for one on a catalog version, the
catalog (`namespace/key`) in the log context, so structured log output and the database log name
what a line was written for, across nested commands and work handed to other threads.
