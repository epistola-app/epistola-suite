---
type: feat
scopes: [security]
audience: dev
title: A local user can be given its own sandbox tenant.
---

`sandbox: true` on an `epistola.auth.local-users` entry routes that login through the same `LoginMembershipResolver` the OIDC path uses, so it lands in a tenant derived from its username instead of a shared one. Per user rather than per profile, because both shapes are wanted at once: an operator account administering a known tenant beside training accounts that each want a private sandbox. Falls back to the configured `tenant` — with a warning — when no resolver is present, so the same file still works under plain `local`.
