---
type: fix
scopes: [exchange]
audience: user
title: A namespace withdrawn by an organization no longer blocks the whole connection.
---

Suite only learns what a tenant may publish into when that tenant authorizes, so between authorizations it could believe it still held a namespace it had lost — submit anyway, be refused, and mark the entire connection blocked, stopping every other catalog. A refusal now prompts a re-read of what Exchange actually grants: a withdrawn namespace defers that one catalog with the reason recorded and leaves the connection working, while a genuine refusal of the connection still blocks it. The refreshed list then stops anything new being queued there, and the catalog page says the binding was revoked and whether there is anywhere left to move to.
