# ADR 0043: Searching a tenant's catalogs and resources

- **Status:** Proposed
- **Date:** 2026-10-30
- **Deciders:** Epistola team
- **Tags:** search, ui, catalog, resources, mcp, database
- **Related:** [ADR 0033](0033-reading-any-release.md) (one content shape), [ADR 0036](0036-one-table-for-every-catalog-version.md)
  (one table for every catalog version), [ADR 0039](0039-catalog-knows-no-content-type.md) (catalog knows no
  content type), [ADR 0010](0010-strict-script-src-csp.md) (strict CSP)

## Context

There is no way to find something across a tenant. A person who wants the "Reminder letter" template
has to know which catalog holds it, open that catalog and scan its templates list; with several
catalogs, supplied ones among them, that is several pages for one lookup. Each list has a search box,
but it searches one kind in one catalog.

What people ask for is the usual one: a search reachable from every page, opened with ⌘K (Ctrl+K),
that finds catalogs and resources by name. Searching _inside_ resources (the text of a template, the
fields of a data contract, the entries of a code list) is wanted later, and the design must not stand
in its way.

What the storage already gives (ADR 0033, ADR 0036):

- **One table names every resource of every version.** `release_resources` holds, per catalog version,
  one row per resource of any type: type, key, name, description, `content_hash`, `updated_at`,
  `updated_by`, and for a variant its template (`parent_id`). A search on names needs no new storage and
  no knowledge of resource types.
- **The version a page shows by default** is `catalog_current_versions`: a catalog's next version, which
  for a catalog that cannot be changed here is the release it runs.
- **Content is stored once per hash.** `resource_contents` is keyed by `(tenant_key, content_hash)`, and
  `ResourceContentStore.write` extracts `resource_references` from a content in the same transaction
  that stores it, collected with it by foreign key. A content never changes once stored.
- **A draft knows its base** (`catalog_versions.base_version_id`), so "changed since the last release"
  is a comparison of two version rows of the same table.
- **Extensions may be missing.** `pg_trgm` and `unaccent` are installed on some databases and not on
  others; the suite must work on both, and the application may not run as the database owner (the
  chart's two-role setup), so it cannot install them.

The suite is clustered and its storage is Postgres only (2.0); whatever search keeps must be consistent
across nodes without a component of its own.

## Decision

### One query, behind the mediator

Search is one query in core, in a new `search` package:

```kotlin
SearchTenant(tenantKey, text, filters: SearchFilters, sort: SearchSort, page) : Query<SearchResults>
```

It implements `RequiresPermission(TEMPLATE_VIEW)` and returns a named result (`SearchResults`: groups of
hits, a count per kind for the filter chips, and the page). Every surface reads it: the web palette and
search page, and an MCP `search` tool. A REST endpoint waits for a contract change and is not part of
this decision.

The query runs as **one SQL statement**: catalogs, and the `release_resources` rows of each catalog's
`catalog_current_versions` version, scoped to the tenant, matched, filtered, grouped, ranked and limited
in the database. Nothing is sorted or limited in application code.

### What it searches

- **The current tenant.** Platform pages, which have no tenant, are not searched yet.
- **Catalogs**, by name, key and description.
- **Resources**, by name and key (description weighs less): every type in `release_resources`, so a
  new resource type is searchable without a change here. A variant is found as "Template › Variant".
- **Each catalog's default version only** (`catalog_current_versions`): not older releases, not
  parallel drafts.
- **Supplied catalogs too** (System, the demo catalog), ranked below the tenant's own and marked
  read-only in the result.

### Matching, with or without extensions

There is **no search index for names**. A tenant has hundreds to a few thousand resources; narrowed to
the tenant and the default versions first, matching their names is milliseconds. An index would add a
write path, a tokenizer and a backfill to speed up what is already fast.

Matching is tiered, and the tier is the main part of the rank:

1. exact name or key;
2. name or key starts with the text;
3. a word of the name starts with the text;
4. the name contains the text;
5. the characters of the text appear in order in the name (`invce` finds "Invoice"): a plain
   `LIKE '%i%n%v%c%e%'`, as an editor's quick-open matches.

Case is ignored throughout (`lower()`).

**`pg_trgm` is used when present, never required.** Each node checks `pg_extension` once at start;
`epistola.search.trigram=auto|on|off` overrides it, so tests force either path. With it, `similarity()`
joins the rank and admits typos the in-order match cannot (`invocie`), and enables grouping near-duplicate
names (below). No trigram index is created: the candidate set is small, and a migration that depends on
an extension being present is a migration that behaves differently per database. `unaccent` is treated
the same way.

Both paths are tested: the shared statement and the plain path on the ordinary test database, the
trigram path with `pg_trgm` installed in the test container.

### Filters

`SearchFilters`, each a `WHERE` on the same statement:

- **kind** (template, stencil, theme, image, font, attribute, code list, catalog);
- **catalog**;
- **own or supplied**;
- **changed since the last release**: the draft row against its base version's row, a different
  `content_hash` or no base row at all.

In the palette they are chips under the input, kinds with their counts, and typed prefixes for those
who prefer them: `type:template`, `in:letters`, `is:changed`, `is:own`. `SearchQuery.parse` turns the
typed text into text and filters, and is unit-tested on its own; a token it does not know stays text.

**The palette carries the context it is opened in**: opened inside a catalog, an "In Letters" chip is
set. It stays visible and removable when it empties the result, so the reader can always widen again
(`.agents/rules/ui-affordances.md`).

### Grouping, before the limit

Results are grouped, and **grouping happens in the statement, before the limit**: the page and the
"top eight templates" count groups, not rows, so one resource is never shown three times and a group is
never split across pages.

1. **By kind**: Catalogs, Templates, Themes, … as sections.
2. **Variants under their template** (`parent_id`).
3. **One resource across catalogs**: the same type and key in several catalogs is one hit naming them
   ("Brand theme — in Letters, Forms"); byte-identical content (`content_hash`) groups the same way.
4. **Near-duplicate names** ("Invoice", "Invoice (copy)") where `pg_trgm` is present: a follow-up, not
   part of the first step.

### Sorting

- **Best match** while text is typed: the tier, then `similarity()` when available, then own before
  supplied, then the most recently changed.
- **Recently changed** (`updated_at`) when nothing is typed, and as a choice on the search page.

"Recently opened" needs a record of views that does not exist; it is left for later, when it would be a
small per-user, bounded store of its own, not derived from the audit log.

### The surfaces

- **Web.** A search button in the bar of every page, with its shortcut shown, and ⌘K / Ctrl+K anywhere,
  open a palette (`<dialog>`). The input queries a UI route, `GET /tenants/{tenant}/search?q=…`, with a
  short debounce, which returns the grouped results as an HTMX fragment; the route never touches
  `/api/**`. Arrow keys move, Enter opens, Escape closes. The shortcut and the keyboard handling live in
  a static script as delegated listeners (ADR 0010). "See all" opens a search page on the same route with
  the filters beside the results and pagination in the database.
- **MCP.** A `search` tool on `SearchTenant`, so an agent finds a template by name.
- **REST.** Later, with a contract change.

The palette ships behind a feature toggle at **beta**, so its shape may still change without a major
release.

### Searching inside resources, later

When content search is built, it uses **Postgres's built-in full-text search**, which is an inverted
index (GIN) and needs no extension:

- **Each resource type says what of it is searchable**, through a `searchText(resource)` method on
  `ResourceType`, beside `references(...)`: a template's text and its data contract's field names, a
  code list's entries, a theme's fonts. The catalog stays type-agnostic (ADR 0039).
- **Extracted where references are**: in `ResourceContentStore.write`, in the same transaction, into a
  table keyed by `(tenant_key, content_hash)` with a `tsvector` (the `simple` configuration, language
  neutral) and a GIN index. A content is extracted once; a released version is never re-indexed; a
  content collected takes its text with it. Existing content needs a one-off backfill.
- **Queried through the same spine**: joined to `release_resources` by `content_hash`, so the default
  version, filters, grouping and sorting apply unchanged. Prefix matching is `to_tsquery('simple', 'inv:*')`;
  name hits rank above content hits; `ts_headline` gives the snippet.

Which fields each type makes searchable, and whether long content needs anything beyond this, are
decided when content search is built and can be measured.

## Options considered

- **Our own inverted index in plain tables** (terms and trigrams per entry, our own tokenizer). It would
  give typo tolerance without `pg_trgm`, but for names there is nothing to speed up, and for content the
  built-in GIN `tsvector` already is an inverted index with prefix matching. It would cost a write path,
  tokenizer versioning and backfills for little. Rejected.
- **An embedded engine (Lucene in the application).** Mature, but every node of a cluster would keep its
  own copy, rebuilt at start and kept in step on every change. Rejected.
- **An external engine (OpenSearch, Meilisearch).** Another component to run, keep consistent and keep
  tenants apart in, against a Postgres-only 2.0. Rejected; `SearchTenant` keeps the engine behind one
  query should that change.
- **Requiring `pg_trgm`.** Simplest matching, but not every database has it and the application cannot
  install it. Rejected in favour of using it when present.
- **ILIKE on names only, nothing more.** What the first step mostly is; the tiers, the in-order match and
  the optional trigram rank are what make it forgiving enough for a palette.

## Consequences

- One new query and one new UI route; no new table and no migration for the first step.
- A new resource type is searchable by name the day it is stored in `release_resources`.
- Ranking differs slightly between databases with and without `pg_trgm` (typo tolerance and near-duplicate
  grouping); the plain path is complete on its own, and both are tested.
- Content search later adds one table, one `ResourceType` method and a backfill, and changes nothing in
  the query's filters, grouping or sorting.
- The palette is beta: its keyboard shortcuts, chips and prefixes may change in a minor release.

## Delivery

Stacked pull requests off `release/2.0`, after this ADR is accepted:

1. `SearchTenant` with filters, grouping and sorting, and its tests, both trigram paths.
2. The palette and the search page, behind the beta toggle.
3. The MCP `search` tool.
