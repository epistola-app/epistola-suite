---
type: feat
scopes: [exchange]
audience: user
breaking: true
title: A catalog's namespace is always chosen explicitly.
---

The tenant default no longer binds a catalog behind the scenes — it only pre-fills the picker, because the choice becomes permanent once a release reaches Exchange and a fallback should not make that decision. Until a catalog has a namespace nothing is queued at all, so there is no longer a queue of work that cannot move; the local release is unaffected either way, and the release can be published later once a namespace is set. The per-catalog namespace _preference_ is gone: the binding is now the single setting.
