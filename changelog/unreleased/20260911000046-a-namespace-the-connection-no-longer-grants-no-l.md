---
type: fix
scopes: [exchange]
audience: user
title: A namespace the connection no longer grants no longer blocks the whole connection.
---

A catalog bound to a withdrawn namespace used to be submitted anyway, refused with HTTP 403, and mark the connection blocked — stopping every other catalog in the tenant and blaming the wrong thing. That publication is now deferred with the reason recorded and resumes by itself if the grant returns; publications Exchange already accepted are still followed to their outcome.
