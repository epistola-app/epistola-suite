---
type: docs
scopes: [catalogs]
audience: dev
title: Resource relocation targets an ID-first model.
---

ADR 0014 now accepts Option F: identity, location, and address are separated so a move is a single column update with nothing to rewrite. Adds a sequenced per-table migration plan, ordered by measured foreign-key coupling, and surfaces the generation-history decision that moving templates forces.
