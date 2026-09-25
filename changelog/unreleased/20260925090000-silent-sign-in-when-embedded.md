---
type: feat
scopes: [auth, embedding]
audience: user
title: An embedded Epistola signs single-sign-on users in without a click too.
---

The login page skipped its silent single-sign-on attempt inside an iframe, because identity providers usually refuse to be framed. With `epistola.embedding.enabled` on, sign-in already has to work inside the frame, so the provider allows the embedding host anyway. The silent attempt now runs there as well, and a user with a live provider session is signed in straight away. Deployments without embedding keep skipping it inside a frame. A failed attempt shows the normal login page, as before.
