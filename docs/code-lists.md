# Code lists

Code lists are named, reusable collections of `{code, label}` entries that
attribute definitions can bind to as their value constraint. They give a third
option alongside the existing _free format_ and _inline allowed values_ kinds:
attributes that need a curated, often-large set of values (ISO locales, country
codes, a tenant's region taxonomy) can point at a code list rather than copy
the values into every attribute.

## When to use a code list

| If you want…                                                      | Use                                                                 |
| ----------------------------------------------------------------- | ------------------------------------------------------------------- |
| any string value                                                  | **Free format** — leave `allowedValues` empty, no code-list binding |
| a short, attribute-specific list (e.g. `brand=acme\|rocket`)      | **Inline allowed values**                                           |
| a longer list shared across attributes, or sourced from elsewhere | **Code list**                                                       |

A custom `audience` attribute with five tenant-specific values is fine as
inline allowed values. A `locale` attribute constrained to BCP-47 — hundreds of
entries, curated by a single source — wants a code list.

## Concepts

A **code list** is `(tenant_key, catalog_key, slug)`-scoped, mirroring
attributes. Each entry has:

| Field        | Description                                                                                                                                                               |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `code`       | The persisted value on the variant attribute (e.g. `nl-NL`).                                                                                                              |
| `label`      | The human-readable text shown in pickers (e.g. `Dutch (Netherlands)`).                                                                                                    |
| `sort_order` | Display order in pickers. Ties broken by `code` ascending.                                                                                                                |
| `hidden`     | When true, filtered from pickers but still **accepted by validation**. Existing variants that already use a hidden code keep working — this is the soft-deprecation path. |

A code list has a **source type** that drives how its entries are managed:

- **`INLINE`** — entries entered directly in the UI; edited in place.
- **`URL`** — entries fetched over HTTPS from a tenant-managed endpoint;
  populated and refreshed on demand via the "Refresh from source" action.
  Falls back gracefully on transient fetch errors: existing entries are kept
  and `last_refresh_error` is recorded.
- **`CLASSPATH`** — entries shipped at a `classpath:` URL inside Epistola.
  Reserved for future bundled-content imports; not exposed in the user-facing
  CRUD in this iteration.

## Binding an attribute to a code list

An attribute is constrained in exactly **one** of three ways:

1. Free format — `allowedValues` empty and `codeListSlug` null.
2. Inline values — `allowedValues` non-empty and `codeListSlug` null.
3. Bound to a code list — `codeListSlug` non-null and `allowedValues` empty.

The commands refuse (2) and (3) together. An attribute is stored as JSON (ADR 0033), and its
binding is what the wire form holds: `codeListBinding: { catalogKey, key }`, the alias the
attribute's catalog knows the list's catalog under (none for its own catalog) and the list's key.
There is no foreign key. A write refuses a list that does not exist (`CodeListNotFoundException`);
the binding is indexed in `resource_references`, and every question about it resolves the alias
through `resolve_catalog_alias`:

- **In use** — `DeleteCodeList` refuses a list an attribute of a current version binds
  (`CodeListInUseException`: unbind the attributes first), and discarding a draft refuses when an
  attribute of another draft binds a list only that draft holds.
- **Moved or renamed** — relocation rewrites the binding of every draft's attribute.
- **Which version** — a binding to a list of another catalog reads it at the version the attribute's
  catalog declares: a draft that pins that catalog's release validates and offers that release's
  entries, not the list's newer draft.

## Validation flow

Variant validation has two phases on every write:

1. The attribute's slug must exist in the tenant's attribute registry — same
   as before. (Note: this iteration looks up by slug only; if the same slug
   exists in multiple catalogs of a tenant, `associateBy` silently picks one.
   Catalog-qualified variant attribute references are a known follow-up; see
   "Future work" below.)
2. The attribute's value must satisfy the constraint:
   - free format — any value is accepted,
   - inline values — value must be in `allowedValues`,
   - code-list binding — a single SQL existence check over the list's content
     (`entries @> [{"code": …}]` on `current_code_lists`) confirms the code is
     present. **Hidden entries are accepted** so variants that referenced
     now-hidden codes keep working.

The check reads one content by its key and scans its entries in the database:
about 0.8 ms at 1,000 entries and 1.6 ms at 10,000 (`CodeListStoragePerfTest`).

## Refreshing a URL-sourced list

The expected payload at the URL is a JSON array of entries:

```json
[
  { "code": "en-US", "label": "English (United States)", "sortOrder": 1 },
  { "code": "nl-NL", "label": "Dutch (Netherlands)", "sortOrder": 2 }
]
```

`sortOrder` and `hidden` are optional. A refresh stores the fetched entries as
the list's new content and points the draft's row at it, in one transaction;
entries that hash the same as the stored content write nothing new, so the
revision and a ready mark stay. Either way the refresh is recorded in
`code_list_refreshes` (`last_refreshed_at`, `last_refresh_error`), which is the
draft's state, not content.

Atomic from any reader's perspective. If the HTTP fetch fails the existing
entries are kept and `last_refresh_error` is recorded — the picker keeps
working while you investigate.

Source URLs must point at `.json` files and follow the same rules as catalog
sources ([Catalog and code-list sources](exchange/README.md#catalog-and-code-list-sources)):
`https:` to a public address; `file:` only inside `epistola.catalog.file-roots`
(off by default); `http:` only with `epistola.codelists.allow-http=true` (dev
only); `classpath:` only under `epistola/catalogs/`, where Epistola ships its own
catalogs. A URL that breaks these rules is
refused when the code list is created or its URL changed (`SOURCE_URL_NOT_ALLOWED`
on `sourceUrl`), and again on every refresh. A failed refresh stores a generic
`last_refresh_error`; the cause is in the server log.

## UI surface

- **`/tenants/{id}/code-lists`** — list page, filterable by catalog. Each row
  shows source type and `last_refreshed_at`; a `refresh failed` badge appears
  when the most recent refresh errored.
- **`/tenants/{id}/code-lists/new`** — create form. The source-type radio
  switches between an inline entries editor (`INLINE`) and a URL field (`URL`).
- **`/tenants/{id}/catalogs/{catalog}/code-lists/{slug}`** — detail page. Entries
  table with a per-row "Hide / Unhide" toggle (HTMX `PATCH`); "Show hidden"
  toggle; "Refresh from source" button for URL-sourced lists; "Delete" guarded
  by the in-use check.
- **`/tenants/{id}/attributes/new`** — three-way "Value constraint" picker
  with free format / inline / bound to code list. The code-list `<select>` is
  grouped by catalog so users see _which_ catalog supplies each list.
- **Variant create/edit dialogs and filter bar** — when an attribute is bound
  to a code list, the dropdown options come from the list's entries (visible
  only) and render as `<code> — <label>` for codes that differ from their
  label, `<code>` alone otherwise.

## Storage

A code list is stored as JSON (ADR 0033): one content per version, draft and
release alike, by hash in `resource_contents`, named by a `release_resources`
row of the draft or release.

```text
{ "resource": { "key", "name", "description", "entries": [{ "code", "label", "sortOrder", "hidden" }], "type": "codeList" },
  "source":   { "type": "URL", "url": "https://…", "auth": "BEARER", "credential": "<id>" } }
```

- `resource` is the wire form (`CodeListResource`): what a release holds, what
  export writes and what the contract fingerprint covers. Entries are kept in
  list order (sort order, then code).
- `source` is this suite's own setting, left out for an INLINE list and absent
  from anything received or exported.
- `credential` names a row of `resource_credentials` by id; the secret is
  encrypted there (`docs/encryption.md`) and never part of the content.
- Lists, existence checks and hiding run in SQL over the content
  (`current_code_lists`); nothing pages in memory.
- An attribute binds a code list by its identity (`code_list_resource_id`, a
  foreign key to `catalog_resources`).

## API reference

The REST API surface is unchanged in this iteration — variants continue to
receive `{ key, value }` attribute pairs without a catalog dimension. The
variant attribute storage shape is also unchanged (slug-keyed JSONB). See
"Future work" below for the catalog-qualified-references roadmap.

## Future work

Tracked separately; not part of this iteration. Issues raised while
building this feature:

- **[#391](https://github.com/epistola-app/epistola-suite/issues/391)** —
  harden `CodeListClient` URL fetches (content-type validation; the Accept
  header and a response size cap are in place).
- **[#393](https://github.com/epistola-app/epistola-suite/issues/393)** —
  rework the variant create/edit dialogs so the list of attributes can
  grow without flooding the form with empty rows.
- **[#396](https://github.com/epistola-app/epistola-suite/issues/396)** —
  cross-variant attribute validation. The current per-variant validator
  is `1+K` queries (1 for definitions, K for code-list existence). Fine
  for UI writes (K = 1-3, rounding error); becomes a cliff for bulk
  paths once the catalog protocol carries code-list bindings. The
  cross-variant API ships alongside that protocol work so the new
  call site uses the right shape from day one.
- **[#397](https://github.com/epistola-app/epistola-suite/issues/397)** —
  encrypt code list credentials (now `resource_credentials.credential`) and
  `catalogs.source_auth_credential` at rest.

Larger follow-ups requiring coordination with `epistola-contract`:

- **Variant attribute references become catalog-qualified.** Today,
  `validateAttributes` looks up attribute definitions by slug only across all
  the tenant's catalogs (`associateBy { it.id.value }`). If the same slug
  exists in two catalogs, the validator silently picks one. With the bundled
  system catalog now contributing `locale`/`language`/`country` to every
  tenant, this latent collision is reachable in practice. A follow-up
  reshapes variant `attributes` to `{ "catalog.slug": value }` and adds a
  `catalog` field to `VariantSelectionAttribute` in the REST API. This
  requires a coordinated change in the `epistola-contract` library.
- **Per-entry deprecation timeline.** Columns like `hidden_at` and
  `replaced_by` for soft-deprecation workflows.
- **ISO 4217 currency code list** + a `currency` reserved attribute.
- **Revisit boot-time auto-upgrade of the system catalog.**
  `SystemCatalogBootstrap` currently dispatches `UpgradeCatalog` for every
  tenant on the previous bundled version. Cheap and works pre-production,
  but once tenants are live this is an unattended write on every deploy:
  a regressive bundle (entry removed, slug renamed, code-list shape
  changed) would cascade across every tenant before anyone notices. We
  should consider gating auto-upgrade behind an explicit toggle (e.g.
  `epistola.system-catalog.auto-upgrade.enabled`, default off in prod),
  or moving to an opt-in path: bundled bumps land in the JAR but the
  upgrade only runs when an operator triggers it per tenant (UI button
  or admin endpoint).

Shipped since the first iteration (no longer future work):

- **Reserved attributes + bundled "system" catalog.** A classpath-sourced
  catalog Epistola supplies, installed by `CreateTenant` for every new tenant,
  supplying `locale`, `language`, and `country` bound to BCP-47 (curated),
  ISO 639-1 (full), and ISO 3166-1 alpha-2 (full) respectively. Versioned
  via the manifest `release.version`; a `SystemCatalogBootstrap`
  `ApplicationRunner` walks every tenant on application start and dispatches
  `UpgradeCatalog` for any that are still on a previous version. See
  `modules/epistola-core/src/main/resources/epistola/catalogs/system/`.
- **Catalog-protocol distribution.** `CatalogManifest` (in
  `epistola-contract` 0.4.0+) carries a `CodeListResource` variant and a
  `DependencyRef.CodeList` discriminator; `AttributeResource` carries a
  `codeListBinding` field. The suite-side `InstallFromCatalog` dispatch and
  `DependencyResolver` walk these edges so remote catalogs can publish code
  lists and attributes that bind to them, with the same plumbing templates
  and stencils already use.

## Testing

Backend tests live under
`modules/epistola-core/src/test/kotlin/app/epistola/suite/attributes/codelists/`:

- `CodeListCommandsTest` — full CRUD (create, update, delete, hide entry),
  tenant scoping, deletion blocked by bound attributes, cascade-delete of
  entries.
- `CodeListClientTest` — file fetch inside the configured root, refusal of `classpath:`
  outside the bundled catalogs and of files outside the roots, URL scheme allowlist, malformed-URL and path-traversal
  rejection, input validation code.
- `OutboundUrlGuardTest` / `OutboundHttpFetcherTest` — the shared source rules
  (addresses, schemes, file roots, redirects, size cap, DNS rebinding).
- `RefreshCodeListTest` — `RefreshCodeList` end-to-end against a real
  `com.sun.net.httpserver.HttpServer`: happy path (entries fetched,
  `last_refreshed_at` advances), failure path (server 500 leaves entries
  intact and populates `last_refresh_error`), `Bearer` and `X-API-Key`
  auth header propagation, INLINE-refresh refusal.
- `SuppliedCatalogCodeListsReadOnlyTest` — every mutating code-list command
  (Create / Update / Delete / Refresh / UpdateCodeListEntryHidden) raises
  `CatalogReadOnlyException` against a catalog Epistola supplies.
- `AttributeCodeListBindingTest` — XOR validation between inline values and
  code-list bindings; half-specified bindings rejected; FK violation surfaces
  when binding to a non-existent list; transitioning the binding both ways.
- `AttributeValidationCodeListTest` — variant validation accepts valid codes,
  rejects unknown codes with a clear message, accepts _hidden_ codes (the
  soft-deprecation contract), and exercises cross-catalog binding within a
  tenant.

Run them with:

```bash
./gradlew :modules:epistola-core:test --tests "app.epistola.suite.attributes.codelists.*"
```

Browser-facing tests live under
`apps/epistola/src/test/kotlin/app/epistola/suite/ui/CodeListUiTest.kt`:

- create an INLINE code list via the new-form's entries editor and verify
  the detail page renders the entries,
- bind a new attribute to a code list via the three-way constraint picker
  and verify the binding badge on the attribute list,
- variant create dialog renders dropdown options as `code — label`,
- hidden entries are filtered out of the variant dropdown,
- the catalog filter on the code-list list page narrows the rows.

Run them with:

```bash
./gradlew :apps:epistola:uiTest --tests "app.epistola.suite.ui.CodeListUiTest"
```
