---
type: refactor
scopes: [catalogs]
audience: dev
title: Templates are keyed by identity, not by where they live.
---

The last and largest of the seven: a template's address sat in the primary key of eight tables across three modules, and relocation had been weakening those foreign keys to `ON UPDATE CASCADE` to make a move work — the option [ADR 0014](docs/adr/0014-safe-catalog-resource-relocation.md) rejected, and a direct contradiction of its own rule that no foreign key is weakened to make a move succeed. Variants, versions, contract versions, activations, quality findings and load-test runs now name the template itself, so a move updates one row and every cascade is gone. Generation history still records the address it was produced at, but gains a backfilled `template_resource_id` so a renamed template's documents stay findable. Two latent holes closed along the way: a generate and a load-test start each accepted a version identified only by catalog, variant and number, so a version of a _different_ template in the same catalog satisfied the check.
