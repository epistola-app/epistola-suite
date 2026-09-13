---
type: feat
scopes: [embedding]
audience: dev
title: Epistola's UI can be embedded in an iframe on epistola.app, demo-mode only.
---

Adds a `postMessage` bridge (`epistola.embedding.*` config, gated CSP `frame-ancestors`) so a host page can request typed-identity navigation and receive navigation/resource-changed notifications; epistola-suite ships no training content itself.
