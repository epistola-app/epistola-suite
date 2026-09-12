---
type: refactor
scopes: [catalogs]
audience: dev
title: Code lists are keyed by identity, not by where they live.
---

The first type taken to the model ADR 0014 accepted. Entries and attribute bindings now reference the code list itself rather than a copy of its address, so moving or renaming one updates a single row and cascades nothing. Queries that need the address read it from `code_lists`, which leaves the REST, export and UI shapes unchanged. An attribute bound to an address that resolves to nothing is still refused by the foreign key rather than silently stored as unbound.
