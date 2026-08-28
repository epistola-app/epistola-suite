# Catalog resource identity migration

Plan for making every catalog resource relocatable by separating **identity** from **location** and
**address**. Implements the target model in
[ADR 0014](adr/0014-safe-catalog-resource-relocation.md).

## Why

A resource's address — `(tenant_key, catalog_key, slug)` — is currently also its primary key. That
one decision is the source of every difficulty in moving a resource between catalogs: references
name an address, so changing location changes identity, so every reference has to be rewritten,
aliased, or blocked.

Exchange between installations genuinely must be address-based, because tenant-local UUIDs are not
portable. That is a requirement on the **wire**. It was applied to **storage**, where it was never
needed.

The end state is that a move is one statement:

```sql
UPDATE stencils SET catalog_key = ? WHERE tenant_key = ? AND resource_id = ?
```

…plus a slug-collision check. Nothing to rewrite, because nothing internal stored an address.

## Target model

Every resource belongs to exactly one tenant, permanently — cross-tenant transfer is out of scope
(ADR 0014). Within that tenant it has one immutable identity and two mutable attributes. Its
address is what those two attributes compose to, not a third thing stored anywhere.

| Concept                | Representation                          | Changed by    |
| ---------------------- | --------------------------------------- | ------------- |
| **Tenant**             | `tenant_key`, the isolation boundary    | nothing, ever |
| **Identity**           | `resource_id UUID`, never exported      | nothing       |
| **Catalog membership** | `catalog_key` column, not part of a key | a move        |
| **Slug**               | its name within that catalog            | a rename      |

The **address** is `catalog_key` + `slug`, derived at the boundaries that need it. This is what
makes each operation a single-attribute change: a move sets catalog membership, a rename sets the
slug, and neither disturbs identity or anything referring to it. Today both attributes are welded
into the primary key, which is why changing either one breaks every reference.

`tenant_key` leads every key and every foreign key even though `resource_id` is a UUID and unique on
its own. That is deliberate: it makes tenant isolation a property of the schema rather than of each
query remembering a predicate, and it keeps a tenant's rows contiguous in every index. A join that
drops `tenant_key` still returns correct rows but stops using the index — a regression this branch
already hit once, in three `stencil_versions` joins.

Rules:

1. `(tenant_key, resource_id)` is the primary key of every resource table.
2. `UNIQUE (tenant_key, catalog_key, slug)` keeps addresses unambiguous.
3. Child and version tables key on the parent's `resource_id` and **never** denormalise the
   parent's catalog or slug.
4. Internal references target identity: relational ones are `resource_id` foreign keys; in-content
   ones carry `{ target, catalogKey, key }`, where `target` resolves and the address records
   authored intent and remains the fallback for a reference whose target does not exist.
5. Address is computed at the boundary — export, URLs, REST, MCP — and mapped back on import.
6. Aliases become an external-redirect layer for bookmarked URLs and external callers. Expirable,
   and never consulted by internal resolution.

## Invariants during the migration

- **Forward-only, non-destructive.** Every step is add → backfill → verify → swap → drop, each as
  its own timestamped migration. See [`migrations.md`](migrations.md).
- **No step leaves the app unable to boot on the previous schema version** until its swap migration
  has run. Deploys are not atomic with migrations.
- **Old and new constraints coexist** through the swap step, so a failure rolls back to a schema
  that still enforces integrity.
- Backfills are verified by a row-count equality assertion in the same migration, not by inspection.
- `DataPreservationMigrationIT` must stay green at every step — it is the guard that RC1-era data
  survives.

## Sequence

Ordered by inbound foreign-key coupling, measured from the schema. Cheapest and least entangled
first; each step is independently shippable.

| #   | Resource                        | Inbound FKs | Notes                                                             |
| --- | ------------------------------- | ----------- | ----------------------------------------------------------------- |
| 1   | `variant_attribute_definitions` | 0           | No dependants. Proves the recipe end to end.                      |
| 2   | `assets`                        | 1           | From `fonts` (backing asset).                                     |
| 3   | `fonts`                         | 1           | Slug-keyed; exercises the non-`id` key shape.                     |
| 4   | `code_lists`                    | 2           | Both inside `core_code_lists`; one is `ON DELETE RESTRICT`.       |
| 5   | `themes`                        | 2           | `document_templates.theme_*`, `tenants.default_theme_*`.          |
| 6   | `stencils`                      | 3           | Partly done: `stencil_versions` already keys on the parent id.    |
| 7   | `document_templates`            | 7           | Spans **three modules**, includes partitioned history. See below. |

Steps 1–6 are mechanical. Step 7 is a different kind of problem and should not be started until
1–6 have shipped and the recipe is proven.

## The per-table recipe

For resource table `R` with dependants `D₁…Dₙ`:

1. **Add** — `R.resource_id` already exists for all seven types (`V20260822130709`). Add
   `Dᵢ.<r>_resource_id UUID` nullable.
2. **Backfill** — populate each `Dᵢ` from `R` by the current composite address; assert every row
   was matched.
3. **Constrain** — `SET NOT NULL`, add the `resource_id` foreign key **alongside** the existing
   composite one. Both now enforce the same relationship.
4. **Cut over reads/writes** — queries and commands move to `resource_id`. The composite columns are
   still written and still constrained, so this step is reversible.
5. **Swap the key** — make `(tenant_key, resource_id)` the primary key of `R`; replace the address
   uniqueness with `UNIQUE (tenant_key, catalog_key, slug)`.
6. **Drop** — remove the composite foreign keys and the denormalised address columns from `Dᵢ`.

Step 6 is what actually unlocks relocation for that type: once no dependant stores the parent's
catalog, moving the parent is a single column update.

`stencil_versions` is midway through this recipe today — it has the stable parent key (step 3) but
kept its address columns, currently held true by an `ON UPDATE CASCADE` added in
`V20260827121153`. That cascade is the interim; step 6 removes the columns and the cascade with them.

## Templates: the case that needs a decision, not just a migration

`document_templates` is referenced by seven foreign keys across **three modules**:

- core: `template_variants`, `contract_versions`, `documents`, `document_requests`
- `epistola-quality`: two, from the findings ledger
- `epistola-load-test`: one

`documents` and `document_requests` are **`PARTITION BY RANGE (created_at)`** and carry a
three-level composite chain — `document_templates` → `template_variants` → `template_versions` —
every level of which includes `catalog_key`.

This forces a product decision:

> When a template moves catalogs, do historical generation records follow it, or pin to where it
> lived at generation time?

**Recommendation: pin.** A generation record states what happened — this document was produced from
this template, in this catalog, at this time. Rewriting its `catalog_key` on a later move edits
history, and doing it via `ON UPDATE CASCADE` rewrites an unbounded number of partitioned rows
inside the move's transaction, which is operationally unacceptable for what should be a
single-row update.

So generation history takes a different shape from the recipe above:

- add `template_resource_id` for identity, backfilled and constrained;
- **keep** `catalog_key` / `template_key` / `variant_key` / `version_key` as recorded historical
  facts, no longer foreign keys;
- drop the composite foreign keys, which is what currently makes the template immovable.

The same question applies to the quality findings ledger, and the answer is likely the same —
a finding was raised against a subject at a point in time. Confirm before implementing.

## Content references

Separate track from the relational work, and independent of it:

1. Extend the stored reference shape to `{ target, catalogKey, key }` — `target` is the resolved
   `resource_id`, the address stays as authored intent and as the fallback when `target` is null
   because the reference does not resolve.
2. Populate `target` on write, at the same seams that qualify references today —
   `TemplateDocumentPreparation` and the four stencil write paths. Extraction stays behind
   `ResourceReferenceSites` (`catalog/graph/`), which is already the single authority for what
   counts as a reference.
3. Resolve by `target` first, address second, so historical content keeps working untouched.
4. **Export strips `target`** and emits addresses, relative for same-catalog references. This is
   what keeps an exported catalog installable under a different key; a test pins it
   (`StencilCrossCatalogDependencyTest`). Import re-resolves address → `target`.

Immutable published payloads are never rewritten. They resolve by address until they age out; a
version published after this lands carries `target` and survives any move of its dependencies.

## What this deletes

Once a type completes the recipe, its relocation needs none of the machinery the alpha required:

- the plan/preview/fingerprint protocol — nothing to preview when nothing is rewritten
- typed rewrite strategies and the `immutable-relative-reference` blocker
- address reservation, `requireAddressAvailable`, and the alias-release command
- write-time qualification, once `target` is populated

Aliases survive only as external redirects. Keep the preview command for _policy_ checks —
permissions, released catalogs, slug collisions — which remain real.

## Open decisions

1. Generation history: pin or follow (recommendation: pin).
2. Quality findings: same question, likely the same answer.
3. Whether a released or subscribed catalog's resources may move at all, and what the portable
   handoff looks like — deferred in ADR 0014 and unchanged by this plan.
4. Whether external alias redirects expire, and after how long.

## Verification

Per step: `./gradlew unitTest integrationTest`, plus `DataPreservationMigrationIT` explicitly.

Before the swap migration of any table, verify the backfill on a restored production-shaped
snapshot rather than on test fixtures — the failure mode is rows that do not match the composite
address, and fixtures will not have them.

After steps 1–6, `scripts/multi-instance-test.sh all` — the re-keying touches tables the cluster
seams depend on.
