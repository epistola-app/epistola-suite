---
type: fix
scopes: [exchange]
audience: user
title: Reauthorizing can no longer move a tenant to a different Exchange organization by accident.
---

Every catalog binding names a namespace of the organization the tenant enrolled with, and bindings are permanent once published, so an authorization returning a different organization is now refused and the existing connection kept. Moving a tenant between organizations is a deliberate disconnect.
