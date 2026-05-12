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

The DB enforces (2) and (3) are mutually exclusive via the
`attr_constraint_kind_xor` check constraint, and the application layer
rejects half-specified bindings before the constraint is reached.

`code_list_catalog_key` is part of the composite FK target, which means an
attribute can bind to a code list in **any** catalog within the same tenant.
The shared `tenant_key` keeps the relationship within one tenant boundary;
cross-tenant references are not possible.

```text
   variant_attribute_definitions                  code_lists
   ────────────────────────────                   ──────────
   (tenant_key, catalog_key, id)        ────────►  (tenant_key, code_list_catalog_key, code_list_slug)
                                          composite FK, ON DELETE RESTRICT
```

`ON DELETE RESTRICT` on the FK means deleting a code list that is still bound
to an attribute fails at the DB layer. `DeleteCodeList` catches that and
surfaces the clearer `CodeListInUseException`: unbind the attributes first.

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
   - code-list binding — a single SQL existence check against
     `code_list_entries` confirms the code is present. **Hidden entries are
     accepted** so variants that referenced now-hidden codes keep working.

The existence check is a primary-key lookup on `(tenant_key, catalog_key,
code_list_slug, code)`, so the hot path is cheap regardless of how many
entries the code list has.

## Refreshing a URL-sourced list

The expected payload at the URL is a JSON array of entries:

```json
[
  { "code": "en-US", "label": "English (United States)", "sortOrder": 1 },
  { "code": "nl-NL", "label": "Dutch (Netherlands)", "sortOrder": 2 }
]
```

`sortOrder` and `hidden` are optional. The refresh transaction:

```sql
BEGIN;
  DELETE FROM code_list_entries WHERE …;
  INSERT INTO code_list_entries (…) VALUES (…);
  UPDATE code_lists SET last_refreshed_at = NOW(), last_refresh_error = NULL …;
COMMIT;
```

Atomic from any reader's perspective. If the HTTP fetch fails the existing
entries are kept and `last_refresh_error` is recorded — the picker keeps
working while you investigate.

`URL` schemes accepted by default: `classpath:`, `file:`, `https:`. `http:`
can be enabled with `epistola.codelists.allow-http=true` (dev only). URLs
must point at `.json` files; `file:` URLs are rejected if they contain `..`.

## UI surface

- **`/tenants/{id}/code-lists`** — list page, filterable by catalog. Each row
  shows source type and `last_refreshed_at`; a `refresh failed` badge appears
  when the most recent refresh errored.
- **`/tenants/{id}/code-lists/new`** — create form. The source-type radio
  switches between an inline entries editor (`INLINE`) and a URL field (`URL`).
- **`/tenants/{id}/code-lists/{catalog}/{slug}`** — detail page. Entries
  table with a per-row "Hide / Unhide" toggle (HTMX `PATCH`); "Show hidden"
  toggle; "Refresh from source" button for URL-sourced lists; "Delete" guarded
  by the in-use check.
- **`/tenants/{id}/attributes/new`** — three-way "Value constraint" picker
  with free format / inline / bound to code list. The code-list `<select>` is
  grouped by catalog so users see _which_ catalog supplies each list.
- **Variant create/edit dialogs and filter bar** — when an attribute is bound
  to a code list, the dropdown options come from `code_list_entries` (visible
  only) and render as `<code> — <label>` for codes that differ from their
  label, `<code>` alone otherwise.

## Architecture diagram

```text
       ┌───────────────────────┐         ┌──────────────────────┐
       │     code_lists        │  PK     │   code_list_entries  │
       │ (tenant, catalog,     │◄────────┤ (tenant, catalog,    │
       │  slug)                │  FK     │  slug, code)         │
       └─────────────▲─────────┘         └──────────────────────┘
                     │
                     │ FK (tenant_key, code_list_catalog_key,
                     │     code_list_slug) ON DELETE RESTRICT
                     │
       ┌─────────────┴────────────────────┐
       │ variant_attribute_definitions    │
       │ (tenant, catalog, id)            │
       │   + allowed_values JSONB         │
       │   + code_list_catalog_key        │
       │   + code_list_slug               │
       └──────────────────────────────────┘
```

## API reference

The REST API surface is unchanged in this iteration — variants continue to
receive `{ key, value }` attribute pairs without a catalog dimension. The
variant attribute storage shape is also unchanged (slug-keyed JSONB). See
"Future work" below for the catalog-qualified-references roadmap.

## Future work

Tracked separately; not part of this iteration. Issues raised while
building this feature:

- **[#391](https://github.com/epistola-app/epistola-suite/issues/391)** —
  harden `CodeListClient` URL fetches (Accept header, max response size,
  content-type validation).
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
  encrypt `code_lists.credential` (and `catalogs.source_auth_credential`,
  which follows the same pattern) at rest.

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
  SUBSCRIBED catalog installed by `CreateTenant` for every new tenant,
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
- `CodeListClientTest` — classpath fetch, URL scheme allowlist, malformed-URL
  rejection, path-traversal rejection.
- `RefreshCodeListTest` — `RefreshCodeList` end-to-end against a real
  `com.sun.net.httpserver.HttpServer`: happy path (entries fetched,
  `last_refreshed_at` advances), failure path (server 500 leaves entries
  intact and populates `last_refresh_error`), `Bearer` and `X-API-Key`
  auth header propagation, INLINE-refresh refusal.
- `SubscribedCodeListsReadOnlyTest` — every mutating code-list command
  (Create / Update / Delete / Refresh / UpdateCodeListEntryHidden) raises
  `CatalogReadOnlyException` against a SUBSCRIBED catalog.
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
