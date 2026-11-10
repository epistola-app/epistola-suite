---
type: fix
scopes: [security, catalog]
title: A catalog or code-list source that answers slowly is given up on after a minute.
---

A source sending its response a little at a time held the thread reading it for as long as it
kept sending, since only each read was timed. A response now has 60 seconds in all, and a catalog
assembled from a manifest URL reads no more in total than its archive may expand to, within five
minutes.
