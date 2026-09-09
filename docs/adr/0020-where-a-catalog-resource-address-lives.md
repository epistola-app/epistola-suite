<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0020: Where a catalog resource's address lives

- **Status:** Proposed
- **Date:** 2026-09-09
- **Discussants:** Epistola team
- **Tags:** catalog, resources, schema, identity, relocation

## Context

[ADR 0014](0014-safe-catalog-resource-relocation.md) separated three things that used to be one:
**identity** (`resource_id`, immutable), **address** (`type` + `catalog_key` + key, public and
mutable), and **alias** (an address a resource used to occupy). The re-keying that implements it
lands in `V20260905090000`–`V20260905090200`.

It left one question unanswered, raised in review of PR #869:

> why do we only have historical aliasses/adresses here? why not all? that way the resource tables
> can get rid of several columns. However, the primary lookup may slow down as well i guess?

The observation is correct, and sharper than it first looks. **A resource's current address is
stored twice.** It is on the resource's own row, and it is on its `catalog_resources` registry row.
The two are kept in step by `sync_catalog_resource_identity`, a `BEFORE INSERT OR UPDATE` trigger on
each of the seven resource tables:

```sql
-- themes
tenant_key, resource_id, catalog_key, id, ...      -- address here
-- catalog_resources
tenant_key, resource_id, resource_type, catalog_key, resource_key   -- and here
```

A trigger is a runtime guarantee, not a structural one. `ALTER TABLE ... DISABLE TRIGGER`, a `COPY`
with triggers off, or a future migration that adds a resource table and forgets the two triggers,
and the copies diverge silently — an alias then resolves to an address nothing occupies.

So the question is not stylistic. It is: which copy is authoritative, and can the other be removed?

### What the registry's copy buys

It is a denormalisation, and it earns its place. The **polymorphic** lookups go through it:

- `ResolveCatalogResourceAddress` answers "what is at `theme:brand/corporate`?" with one indexed
  read of `catalog_resources`, plus one of `catalog_resource_aliases` for the historical case.
- `CatalogResourceMovePlanner.resolveIdentity` resolves any type's address uniformly.
- `catalog_resource_aliases.target_resource_id` needs a single table to point into.

Remove it and each of those becomes a `UNION` over seven differently-shaped tables that grows with
every new resource type.

One place does **not** use it, and the reason matters: `GetTenantResourceGraph.loadNodes` still
`UNION ALL`s the seven tables directly. Not an oversight to fix by pointing it at the registry — it
selects each type's human-readable name (`assets.name`, `code_lists.display_name`, …), which is
type-specific and not in the registry. That query would keep its seven arms under **every** option
below, so it is evidence for none of them.

### What the resource row's copy buys

Everything **type-specific**, and two things in particular that a shared registry cannot express.

**Typed keys.** Each resource table's key column is a domain with a `CHECK`, sized for that type:

| Table                | Key column | Domain                                    |
| -------------------- | ---------- | ----------------------------------------- |
| `document_templates` | `id`       | `TEMPLATE_KEY VARCHAR(50)`, slug pattern  |
| `themes`             | `id`       | `THEME_KEY`, slug pattern                 |
| `code_lists`         | `slug`     | `CODE_LIST_KEY VARCHAR(64)`, slug pattern |
| `assets`             | `id`       | `ASSET_KEY`, **UUID-backed**              |

`catalog_resources.resource_key` is a bare `TEXT`, because it has to hold all of them. One column
cannot enforce "slug, at most 50 characters" and "UUID" at once.

**Cascade.** All seven tables carry
`FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs ON DELETE CASCADE`, so deleting a catalog
structurally deletes its resources.

## Considered options

### A. Status quo — address on the resource row, mirrored into the registry

The shape on the branch. The resource row is authoritative for its own address; the trigger
maintains the registry copy for polymorphic lookup.

### B. Registry-only — drop `catalog_key` and the key column from all seven tables

The reviewer's proposal in full. A resource table becomes `(tenant_key, resource_id, <content>)` and
the registry holds every address, current and historical.

Attractive in principle: one address, one place, no trigger, no divergence possible, and an eighth
resource type costs a row in `catalog_resource_types` rather than columns, constraints and triggers.

The costs are concrete:

- **Typed keys are lost.** Seven domain-checked key columns collapse into one `TEXT`. Key validation
  moves from the database to the application — the opposite direction from the house rule that put
  asset media types in a seeded table rather than a Kotlin enum (CLAUDE.md item 15), and from the
  `catalog_resource_types` table added in this very migration.
- **The import upserts lose their conflict target.** Seven `INSERT ... ON CONFLICT (tenant_key,
catalog_key, <key>) DO UPDATE` statements carry the whole import path. A unique index on another
  table cannot be a conflict target. Each becomes a writable CTE (`WITH r AS (INSERT INTO
  catalog_resources ... ON CONFLICT (address) DO UPDATE ... RETURNING resource_id) INSERT INTO
  <table> SELECT ... FROM r ON CONFLICT (tenant_key, resource_id) DO UPDATE`). Correct and still
  atomic, but seven rewrites of the least forgiving code in the catalog domain.
- **Every catalog-scoped read becomes a join.** "List the templates in this catalog", "get the theme
  at this address", and catalog export all filter on `catalog_key` today. 481 Kotlin sites mention
  the column; the dependant-table ones already route through `templateAtAddress` / `templateJoin` and
  friends and would need only those four helper files changed, but the resource tables' own queries
  would each grow a join.
- **Cascade re-chains.** `catalogs → catalog_resources → resource table` rather than
  `catalogs → resource table`. This one is a wash, arguably an improvement.

### C. Invert — registry holds identity and type only, address only on the resource row

Removes the duplication in the other direction, and is cheap. It also removes exactly the thing the
registry exists for: every polymorphic lookup becomes a seven-way `UNION`, and
`catalog_resource_aliases` has nothing single to target. Rejected on sight.

### D. Keep both copies, but make divergence structurally impossible

Replace the trigger's runtime guarantee with a composite foreign key from each resource table into
the registry over `(tenant_key, resource_id, catalog_key, key)`, deferrable so one transaction can
update both rows in either order.

This is the most appealing option on paper — it answers the real objection (a trigger is weaker than
a constraint) while keeping typed keys. **It does not work**, for one specific reason:
`catalog_resources.resource_key` is `TEXT` and `assets.id` is `ASSET_KEY`, a UUID-backed domain.

Checked against PostgreSQL 18 rather than assumed. The slug-keyed types are fine; `assets` is not:

```
ERROR:  foreign key constraint "ast_resource_id_id_fkey" cannot be implemented
DETAIL:  Key columns "id" of the referencing table and "resource_key" of the referenced table
         are of incompatible types: asset_key and text.
```

The option therefore survives for six of seven types and fails for the seventh, which makes it worse
than either uniform alternative. Widening `resource_key` to hold both would mean giving it no type
at all, which is option B.

### E. Split the difference — drop only `catalog_key` from the resource tables

Catalog membership (what a _move_ changes) lives only in the registry; the typed key stays on the
resource row. Halves the duplication and keeps the domains.

But a _rename_ then writes to two tables, so the duplication is not actually removed — only made
irregular, with move and rename behaving differently. The address's uniqueness constraint can no
longer live on the resource table either. Worse than both A and B for being neither.

## Decision

**Keep option A.** The address stays on the resource row, and the registry keeps its copy for
polymorphic lookup.

The duplication is real and the reviewer was right to name it, but it is a **deliberate
denormalisation with a stated owner**: the resource row is authoritative, the registry copy is
derived, and one trigger function maintains the derivation for all seven types. What option B offers
is a single source of truth purchased with database-enforced typed keys — and this codebase has
repeatedly decided that direction the other way.

The trigger's weakness is answered rather than dismissed:

- `SchemaHygieneAppTest` asserts the model that the trigger implements — all seven tables keyed by
  identity, the address still unique, dependants naming identity and not copying an address. A
  resource table added without its two triggers fails there.
- `CatalogResourceIdentityIntegrationTest` covers the paths where divergence would appear:
  insert, upsert-adopting-an-existing-identity, concurrent registration at one address, and delete.

This is a re-evaluation of a decision that is not yet merged, which is the cheapest moment to make
it. It comes out the same way, with the reasoning written down.

## Consequences

- Seven resource tables keep `catalog_key` and a domain-typed key column; `catalog_resources` keeps
  its address copy. No migration changes.
- Adding an eighth resource type means: a row in `catalog_resource_types`, address columns and a
  unique constraint on the new table, and **two triggers**. The last is the easy one to forget, and
  the schema test is what catches it.
- If the typed-key argument ever stops holding — a future where keys are uniformly slugs, or where
  Postgres domains gain polymorphic behaviour — option B becomes strictly better and this ADR should
  be superseded rather than patched.
- The `resource_key`/`ASSET_KEY` type mismatch that kills option D is worth remembering: it is the
  same reason `assets` needs `keyColumnType` in `MovableResource`, and it will keep surfacing.

## References

- [ADR 0014: Safe relocation of authored catalog resources](0014-safe-catalog-resource-relocation.md)
- [`docs/catalog-resource-identity-migration.md`](../catalog-resource-identity-migration.md)
- [`docs/catalog-resource-relocation.md`](../catalog-resource-relocation.md)
- PR #869 review thread on `V20260905090000__core_catalog_resource_identity.sql`
