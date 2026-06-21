# ADR 0007: Query-layer pagination, sorting, and filtering (CQRS)

- **Status:** Accepted
- **Date:** 2026-06-21
- **Deciders:** Epistola team
- **Tags:** cqrs, query, pagination, sorting, filtering, jdbi, sql

## Context

Issue #494 asks for "good UX when there are 100+ resources" on the 15 list pages
(templates, stencils, themes, and ~12 others). The trigger is UI/UX, but the
**capability is a query-layer concern**: a table can only show a sorted page of N
rows if the query can _produce_ one.

This matters because of how the app is layered. The UI handlers and the REST/MCP
surfaces are sibling **adapters** over a single CQRS core — both dispatch the same
queries through `SpringMediator` (e.g. `ListTemplateSummaries(...).query()`);
neither calls the other (enforced by `UiRestApiSeparationTest`). So adding
pagination/sorting at the **query layer** is the single change that can serve
every surface, instead of bolting paging onto one adapter.

Today the list queries already accept `limit`/`offset` (default `limit = 50`) but
**nothing exposes a total, a page, or a configurable sort**, so the UI silently
drops rows past 50 — a latent correctness bug, not just a missing feature.

This ADR decides the **CQRS-level** capability. The UI table that consumes it is a
**separate, later concern** (see "Delivery").

### Decision drivers

- **One capability, all surfaces.** Add it where UI, REST, and MCP already share
  code — the query layer — not in a single adapter.
- **Reusable across 15 queries**, opt-in per query, not a one-off.
- **Safe.** `ORDER BY` and `LIMIT/OFFSET` derive from untrusted input.
- **Simple and explicit.** Prefer named, typed queries (the point of CQRS) over a
  generic ad-hoc query language.
- **Pre-release pragmatism.** Breaking query return types and routes is fine
  (Epistola is not yet in production).

## Decisions

### 1. Shared paging primitives in `epistola-core` (`common.paging`)

They live in `epistola-core` (package `app.epistola.suite.common.paging`,
parallel to `common.ids`), **not** `epistola-web`: a core query _returns_
`PagedResult`, and `epistola-web` depends on `epistola-core` (not the reverse), so
the primitives must sit in core where every surface — UI handler, REST, MCP — can
already see them.

```kotlin
enum class SortDirection { ASC, DESC }
data class SortSpec(val column: String, val direction: SortDirection)  // column = logical key, not SQL
data class PageRequest(val page: Int, val size: Int)                    // 1-based page
data class PagedResult<T>(val items: List<T>, val page: Int, val size: Int, val total: Long) {
    val totalPages: Int get() = if (total == 0L) 1 else ((total + size - 1) / size).toInt()
    val from: Long get() = if (total == 0L) 0 else ((page - 1).toLong() * size) + 1
    val to: Long get() = minOf(page.toLong() * size, total)
}
```

A query opts in by accepting a `SortSpec` + `PageRequest` and returning
`PagedResult<T>` instead of a bare `List<T>`. The primitives are the reusable
surface — **not** a generic table/query framework.

### 2. Pagination model — numbered offset + total (Strapi-style metadata)

`LIMIT/OFFSET` with a known total, surfaced as a metadata envelope modeled on
Strapi's `meta.pagination` (`page` / `pageSize` / `pageCount` / `total`), which
maps directly onto `PagedResult` (`page` / `size` / `totalPages` / `total`).

Chosen over **load-more** (no total, not page-addressable) and **cursor/keyset**
(can't jump to an arbitrary page, awkward with user-chosen sort columns, overkill
for hundreds of rows). Keyset is the documented escape hatch if a single list ever
grows into the many-thousands of rows.

### 3. Count strategy — windowed `COUNT(*) OVER()`

The row query returns `COUNT(*) OVER() AS total_count`, so one round trip yields
the page _and_ the total of the filtered set, computed over the **same `WHERE`**
as the rows — a separate `COUNT(*)` query can drift out of sync as filters are
added. The known cost: a windowed count returns zero rows (hence no total) on an
out-of-range page; the handler clamps `page` to `[1, totalPages]` and re-queries
once in that rare stale-deep-link case.

### 4. `ORDER BY` and `LIMIT/OFFSET` safety

- **`ORDER BY` cannot be a bind parameter**, so each query maps a small set of
  **logical column keys → fixed SQL expressions** (a per-query whitelist) and
  ignores anything else, falling back to a default sort. Direction is rendered
  from the `SortDirection` enum, never from user text.
- **Out-of-range `page` is clamped in the query** to the last page. The windowed
  `COUNT(*) OVER()` returns no rows past the end, so the handler re-fetches page 1
  to learn the total, then fetches the clamped last page — keeping a correct
  total and non-empty rows on a stale deep link.
- **`PageRequest` fails fast at construction** (`require(page >= 1)`,
  `require(size >= 1)`) — a programmatic caller passing `0`/negative gets an
  `IllegalArgumentException`, never a wrong or empty page, and junk never reaches
  SQL. This is the hard floor (defense in depth).
- **The `size` allow-list (e.g. `{10, 25, 50}`) and clamping untrusted URL params to
  sane values live at the UI boundary** (the handler that parses the query
  params, PR2). The core query still accepts any positive page size, so a
  programmatic caller (MCP, internal) is not restricted to the UI's three sizes.

### 5. Filtering — explicit typed params, NOT a generic operator DSL

Filters stay **explicit, typed query parameters per query** (today:
`catalogKey`, `searchTerm` ILIKE, stencil `tag`), added one at a time as a page
needs them.

We explicitly **reject**, for now, a Strapi-style generic filter language
(`filters[field][$eq]`, `$containsi`, `$in`, …):

- It is a large, security-sensitive surface — every _field × operator_ pair must
  be allow-listed and validated, multiplying the `ORDER BY` whitelist problem
  across the whole schema.
- It cuts against CQRS's grain — named, intention-revealing queries are the point;
  a generic operator language turns them back into ad-hoc query endpoints. Strapi
  is a _generic headless CMS_ (querying arbitrary content types is its product);
  Epistola is a _specific product_ with known operations and does not have that
  problem.
- YAGNI — the known consumers are a catalog dropdown and a search box, i.e. two
  specific typed filters. We will not build a query language to serve two
  hardcoded controls.

We **do** adopt Strapi's pagination _metadata_ shape (Decision 2); we do **not**
adopt its filter _language_. If flexible client-driven filtering becomes a real
need (most likely on the REST API for integrators), it gets its own ADR.

### 6. Delivery — two complementary PRs, CQRS first

- **PR1 (this capability):** the primitives (Decision 1) + the safety helpers
  (Decision 4), **applied to `ListTemplateSummaries`** so it returns
  `PagedResult` and accepts a `SortSpec`, with mediator/handler-level tests
  (ordering, offset math, total, page/size clamping, whitelist fallback). The
  existing templates UI handler call-site is adjusted to keep the build green
  (unwraps `.items`) but no new UI is added.
- **PR2 (UI):** the reusable table controls (sortable headers + pagination
  footer), URL-driven state, single HTMX swap target, and demo seed data.

The split is bottom-up but safe because the consumer (the table) is already
designed, so PR1 is scoped to exactly what PR2 needs — not a speculative
framework. Both are designed together; only shipping is split.

## Consequences

- **Latent data-loss bug fixed.** Rows past the first page become reachable
  instead of silently dropped.
- **`ListTemplateSummaries` returns `PagedResult<TemplateSummary>`** and accepts a
  `SortSpec`; its UI/REST/MCP call-sites adapt to the new return type — a
  pre-production breaking change, no migration path needed.
- **All three surfaces gain the capability at the query layer**, but **only the
  web UI exposes it in PR1/PR2.** Whether REST/MCP expose `sort`/`page`/`size` and
  a total to external callers is a deliberate, separately-designed follow-up
  (the REST contract is versioned and external), recorded per CLAUDE.md item 11 —
  not silently drifted.
- **The sortable/filterable surface is explicit and auditable** — each query
  declares its whitelist and its typed filters; nothing else can reach SQL.
- **Reconsider keyset pagination** only if a single list grows to many thousands
  of rows.
- A prior UI-first draft of this ADR (and its spec/trade-offs notes) is parked in
  a git stash, superseded by this CQRS-first decision.

```

```
