---
type: docs
scopes: [exchange]
audience: dev
title: Recorded that Exchange appoints one publisher per catalog.
---

ADR 0018 and the publication guide described several publishers sharing a namespace with the release fingerprint arbitrating collisions afterwards. Exchange has since decided the question the other way (its ADR 0027): a catalog has one appointed publisher and a second is refused when it submits. Comparing fingerprints could never have caught the case that mattered — a second publisher appending a _higher_ version — so it keeps the narrower job of letting the appointed publisher re-submit identical bytes after an ambiguous outcome. The guide also now names the reconnect case and its recovery, both of which are Exchange-side.
