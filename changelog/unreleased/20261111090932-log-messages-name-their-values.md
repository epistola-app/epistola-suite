---
type: refactor
scopes: [logs, observability]
title: Log lines name their values, the command and trace they belong to, and the tenant worked in.
---

Every value in a log message is written as a named pair after a short sentence, such as `Catalog upgrade failed catalog=acme tenant=t-42`, so lines can be searched by what a value is. Most messages changed wording with it: **update alerts and saved searches that match the old text.** Each line also carries the command or query and catalog it was written for, work handed to another thread keeps the request's trace, and work in a tenant's pages is logged under that tenant, also for single sign-on users. New counters: `epistola.catalog.releases{via}` and `epistola.catalog.upstream.checks{outcome}` (`docs/metrics.md`).
