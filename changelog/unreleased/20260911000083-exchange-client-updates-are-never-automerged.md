---
type: chore
scopes: [deps]
audience: dev
title: Exchange client updates are never automerged.
---

A bump moves the wire contract Suite compiles against, and the default rule automerges minor and patch updates — which would put that review back where the typed client was meant to take it from, at runtime.
