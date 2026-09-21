---
type: fix
scopes: [generation]
audience: user
title: Generation history links to a template now include its catalog.
---

The recent-jobs and most-used-templates links on the generation history dashboard pointed at
`/templates/{templateKey}` with no catalog, so a template outside the default catalog linked to the
wrong page. The links, and the query behind them, now carry the job's own catalog.
