---
type: refactor
scopes: [catalogs]
audience: dev
title: Catalog resources are keyed by identity, not by where they live.
---

A resource's address used to sit in the primary key of the tables referencing it, so making a move work meant weakening foreign keys to `ON UPDATE CASCADE` — the option [ADR 0014](docs/adr/0014-safe-catalog-resource-relocation.md) had rejected. All seven types now carry a stable `resource_id`, and the rows that reference them name that instead: a move updates one row and the cascades are gone. Generation history still records the address a document was produced at, and gains a `template_resource_id` so a renamed template's documents stay findable. Two latent holes closed along the way, where a generate and a load-test start each accepted a version identified only by catalog, variant and number — so a version of a different template in the same catalog satisfied the check.
