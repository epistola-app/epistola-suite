---
type: feat
scopes: [catalogs]
audience: user
title: Relocation refuses a move that would make two catalogs depend on each other.
---

Snapshot restore orders catalogs topologically and fails outright on a cycle, so such a move could have left a tenant's snapshots unrestorable — discovered later, by whoever was trying to recover. Required by ADR 0014 and previously unimplemented.
