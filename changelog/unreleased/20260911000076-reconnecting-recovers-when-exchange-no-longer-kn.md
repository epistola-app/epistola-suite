---
type: fix
scopes: [exchange]
audience: user
title: Reconnecting recovers when Exchange no longer knows this installation.
---

Reauthorizing offers the application and connection this tenant already holds, so Exchange renews that identity rather than minting another and stranding the catalogs bound to it. If Exchange has been rebuilt — or restored from before the enrollment — those ids mean nothing to it, and the attempt dead-ended with "Exchange could not be reached or refused the request", leaving "forget locally, then connect" as something to work out. Suite now recognises that answer, drops the stale identity and enrolls afresh in the same click.
