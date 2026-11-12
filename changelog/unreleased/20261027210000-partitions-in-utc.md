---
type: fix
scopes: [operations]
title: Monthly partitions are bounded in UTC, the demo image's migration Job exits, and a saved site banner shows at once.
---

On a node whose JVM ran outside UTC, monthly partitions of documents, results and logs were bounded at local midnights, so rows near the start of a month could land in the wrong partition. The `-demo` image started as the Helm chart's migration Job now migrates and exits instead of failing the install. A site banner saved while another page was being drawn no longer shows the old text on that node for a minute.
