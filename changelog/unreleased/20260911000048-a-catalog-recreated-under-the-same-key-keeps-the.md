---
type: fix
scopes: [exchange]
audience: user
title: A catalog recreated under the same key keeps the namespace it published under.
---

Deleting a catalog discarded its namespace binding while Exchange kept everything published under those coordinates, so a new catalog with the same key could claim a second namespace. The binding now outlives the local catalog and records when a release first reached Exchange.
