# Catalogs & Resource Exchange

Catalogs are first-class entities in Epistola for organizing, sharing, and importing resources. A catalog groups templates, themes, stencils, attributes, images and fonts under a common identity. Every resource belongs to exactly one catalog.

> **This page is the root of the exchange docs.** The architecture, data model, protocol, and import/export flows are below. The **wire contract of each part** is documented separately — see the next section.
>
> See [Catalog contract upgrade compatibility](../catalog-contract-compatibility.md)
> for the explicit breaking shapes and their impact on stored editor content.

## Parts & contract versions

Import/export is a **wire format**: a `catalog.json` manifest plus one detail file per resource. 2.0 reads and writes wire version 8. Its reference is the JSON Schemas in the contract repository, under [`contracts/catalog/schemas/`](https://github.com/epistola-app/epistola-contract/tree/v2.0.0/contracts/catalog/schemas), which also ship in the `epistola-catalog` artifact under `META-INF/epistola-catalog/schemas/`. This repository does not copy them into per-part pages.

**There is one catalog-wide wire `schemaVersion`** (currently `8`) that the whole bundle moves together — the manifest **and** every resource detail carry the same number, so each file is self-describing. The **manifest is authoritative** for it; resources are **not** versioned independently. A catalog is at a single wire version, not a _set_ of per-part versions. Portable version gating and explicit migrations are owned by `epistola-catalog`; version `4` is the accepted baseline and is migrated step by step to `8` before typed binding.

**Wire version 8 schemas:**

| Schema                             | Covers                                                                                                           |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `catalog-manifest-v8.schema.json`  | the manifest: catalog identity, registry and namespace, `release`, the resource index, dependencies              |
| `resource-detail-v8.schema.json`   | every resource detail file: templates, themes, stencils, attributes, images, code lists, fonts, quality profiles |
| `template-document-v8.schema.json` | the template document inside templates and stencils: a flat graph of nodes and slots                             |
| `template-shared-v8.schema.json`   | definitions the template schemas share: page settings, document styles, expressions, theme references            |
| `theme-v8.schema.json`             | a theme's default styles, page settings and named block style presets                                            |

Resources install in the order of [`CatalogConstants.RESOURCE_INSTALL_ORDER`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt).

**Earlier versions** have per-part pages, kept as a record: [`v5/`](v5/) and [`v4/`](v4/), the oldest version 2.0 still migrates on import. They describe the shape at that version, not what 2.0 writes.

**The three version axes** (orthogonal — don't conflate them):

| Axis                             | What it versions                                                | Where                                                                  | Reference                                                 |
| -------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------------- | --------------------------------------------------------- |
| **Catalog wire `schemaVersion`** | the JSON _shape_ of the whole exchange bundle (one per catalog) | the manifest's `schemaVersion` (authoritative); echoed on every detail | [ADR 0007](../adr/0007-catalog-wire-format-migrations.md) |
| **Release version** (SemVer)     | the catalog _content_ at a point in time                        | `release.version`, `catalog_versions`                                  | [catalog-versioning.md](../catalog-versioning.md)         |
| **Content fingerprint**          | content _identity_ (did anything change?)                       | `release.fingerprint`                                                  | [catalog-versioning.md](../catalog-versioning.md)         |

These three are the **catalog-level** axes. For the full picture — including the template, contract and stencil versions 2.0 removed — see [version-axes.md](../version-axes.md).

**Maintaining these docs:** a wire version is defined by its schemas in the contract repository, not by pages here. A shape change that is **not** round-trip-compatible adds the next version's schemas and a migration class to the single chain in `epistola-catalog`. The suite takes it with the contract bump (`CATALOG_SCHEMA_VERSION` follows `CatalogWireSchema.CURRENT_VERSION`); update the version and the schema table and link above at the same time. Do not add per-part pages for a new version, and do not edit the [`v5/`](v5/) and [`v4/`](v4/) records.

> **Implementation status.** `epistola-catalog` owns the portable
> `CatalogSchemaMigrator`. It gates the manifest and every resource detail
> before typed binding at both Suite import paths. The supported window spans
> baseline version `4` through current version `8`, through `CatalogV4ToV5Migration`,
> `CatalogV5ToV6Migration`, `CatalogV6ToV7Migration` and `CatalogV7ToV8Migration`.
> `CatalogV4ToV5Migration` removes legacy `isDraft` markers; exact stored-draft
> resolution that requires Suite database state is handled by the matching Flyway
> migration.

## Concepts

### No catalog types

A catalog has no type (ADR 0032). Every catalog has drafts and releases; it may have a **source**
(`source_url`: a manifest URL, Epistola Exchange, a registry, or `classpath:` for the catalogs
Epistola supplies) that can be asked for newer releases. Releases arrive by import, install or
upgrade; the installed release version is tracked for upgrade detection. See
[`catalog-versioning.md`](../catalog-versioning.md).

Every tenant has a `default` catalog that cannot be deleted. Additional catalogs can be created,
imported or registered from a source.

### Resource Types

Catalogs contain seven resource types, installed in dependency order (see [`RESOURCE_INSTALL_ORDER`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogConstants.kt)):

1. **Images** — Binary images (PNG, JPEG, WebP, SVG) used in templates
2. **Code lists** — Reusable enumerations (e.g. BCP-47 languages) bound by attributes
3. **Fonts** — Asset-backed font families (system/`CLASSPATH` fonts are not exchanged)
4. **Attributes** — Variant attribute definitions (e.g., language, region)
5. **Themes** — Document styles, page settings, block style presets
6. **Stencils** — Reusable content blocks, inserted by reference (no stencil versions since 2.0)
7. **Templates** — Document templates with variants and a data contract, edited in place (no template or contract versions since 2.0)

The wire contract of each (and the manifest) is documented per part under [Parts & contract versions](#parts--contract-versions).

### Catalog Scoping

All catalog-scoped tables use a composite primary key: `(tenant_key, catalog_key, id)`. This means the same slug can exist in multiple catalogs within a tenant. All queries filter by `catalog_key` to ensure isolation.

### Read-Only Enforcement

A catalog can be changed unless the installation is run-only (`epistola.catalog.editing.enabled=false`) or Epistola supplies it (a `classpath:` source). All mutating command handlers call `requireCatalogEditable()`, which checks `GetCatalogEditability`. The UI hides edit/delete controls and shows the reason for a catalog that cannot be changed.

During catalog import, the `CatalogImportContext` ScopedValue bypasses editability checks so that an install can write resources into a catalog that cannot be changed.

## Architecture

Portable catalog structure and behavior live in the external
`app.epistola.contract:epistola-catalog` artifact. It owns the wire models,
registries, validation, archive codec, migration, canonicalization, hashes, and
fingerprints. The matching npm package supplies the editor's portable models
and registry projections.

Suite integration remains in `modules/epistola-core` under
`app.epistola.suite.catalog`. It owns tenant authorization, persistence,
import/upsert orchestration, the release lifecycle, dependency
installation, and conflict handling. Its validators and archive services are
adapters around `epistola-catalog`, not an independent portable specification:

```
catalog/
  Catalog.kt                          # Data model (Catalog, CatalogMetadata, AuthType)
  CatalogClient.kt                    # HTTP/file/classpath fetching
  CatalogContentBuilder.kt            # A version's content: manifest entries, details, binaries
  CatalogImportContext.kt             # ScopedValue for import bypass
  CatalogReadOnlyException.kt         # requireCatalogEditable()
  DependencyResolver.kt               # Transitive dependency resolution
  DependencyScanner.kt                # Scan content for references
  commands/
    CreateCatalog.kt                  # Create a catalog
    RegisterCatalog.kt                # Subscribe to remote catalog
    DeleteCatalog.kt                  # Remove catalog + all resources
    InstallFromCatalog.kt             # Install from a source (receives an archive)
    UpgradeCatalog.kt                 # Upgrade from a source (receives an archive)
    ExportCatalogZip.kt               # Export a release as ZIP
    ImportCatalogZip.kt               # Receive a release from an archive
    ImportArchiveReader.kt            # Read, validate and migrate an archive
  dependencies/                       # Declared catalog dependencies and their pins
  releases/                           # Drafts, releases, theme settings
  sources/                            # Where releases can come from
  queries/
    BrowseCatalog.kt                  # List resources in catalog
    CheckCatalogUpgrade.kt            # Is a newer release available?
    GetCatalog.kt, ListCatalogs.kt
    PreviewInstall.kt                 # Dependency resolution preview
```

UI handlers and routes are in `apps/epistola`, in the `Catalog*Handler.kt` files (`CatalogTransferHandler.kt` holds install and export), bound in `CatalogRoutes.kt` under `/tenants/{tenantId}/catalogs`.

See [Catalog contract upgrade compatibility](../catalog-contract-compatibility.md)
for the exact ownership boundary, production implications, and the catalog-v5
follow-up for RC3 stencil draft markers.

## Data Model

### `catalogs`

| Field                    | Type              | Description                                    |
| ------------------------ | ----------------- | ---------------------------------------------- |
| `id`                     | CatalogKey (slug) | URL-safe slug. Unique per tenant.              |
| `tenant_key`             | TenantKey         | Owning tenant.                                 |
| `name`                   | varchar(255)      | Display name.                                  |
| `description`            | text, nullable    | Optional description.                          |
| `type`                   | varchar(20)       | Unused since ADR 0032; defaults to `AUTHORED`. |
| `source_url`             | text, nullable    | Where releases come from; null for none.       |
| `source_auth_type`       | varchar(20)       | `NONE`, `API_KEY`, or `BEARER`.                |
| `source_auth_credential` | text, nullable    | Credential for authenticated remote catalogs.  |
| `next_version_id`        | uuid, nullable    | The next version (ADR 0036).                   |
| `created_at`             | timestamptz       | Creation timestamp.                            |
| `updated_at`             | timestamptz       | Last modification timestamp.                   |

Primary key: `(tenant_key, id)`. Its release is its highest, a view (`catalog_current_releases`,
ADR 0036); a release installed from the Exchange records `released_via = 'exchange'` and the
Exchange address it came from.

### Releases (`catalog_versions`, released rows)

Immutable release history of a catalog, released here or received: one released row of
`catalog_versions` per release, keyed by `version_id`, with its number (`version`, SemVer),
`fingerprint`, `content_hash`, `notes`, `manifest_snapshot`, `released_at` and `released_by`. Not
parallel installs; the changelog and upgrade-diff source. See
[ADR 0038](../adr/0038-catalogs-in-2-0.md) and [`catalog-versioning.md`](../catalog-versioning.md).

### Resources

Every resource of every version is a row in `release_resources` (template variants as rows of their
own) naming its JSON content in `resource_contents`, stored once by hash; binaries are in
`asset_content` by hash. The 1.x per-type tables (`document_templates`, `template_versions`,
`themes`, `stencils`, `assets`, …) are converted and dropped by the 2.0 migration chain
([migrations](../migrations.md)).

## Protocol Specification

The catalog exchange protocol defines the wire format for sharing catalogs between Epistola instances. It is used for remote catalog URLs, ZIP exports, and the catalogs Epistola bundles. The examples and tables below show wire version 8, the one 2.0 writes; the [schemas](#parts--contract-versions) are authoritative where they say more.

### Catalog Manifest (`catalog.json`)

```json
{
  "schemaVersion": 8,
  "catalog": {
    "registry": "exchange.epistola.app",
    "namespace": "acme",
    "key": "acme-templates",
    "name": "Acme Corp Templates",
    "description": "Official templates for Acme Corp documents.",
    "defaultTheme": { "key": "corporate" }
  },
  "publisher": {
    "name": "Acme Corp"
  },
  "release": {
    "version": "1.0.0",
    "releasedAt": "2026-11-12T00:00:00Z",
    "fingerprint": "v6:9f3a1c2b…"
  },
  "catalogDependencies": [
    {
      "alias": "shared-components",
      "registry": "exchange.epistola.app",
      "namespace": "acme",
      "catalogKey": "shared-components",
      "releaseVersion": "2.3.0",
      "releaseFingerprint": "v6:4be17d0e…"
    }
  ],
  "resources": [
    {
      "type": "template",
      "key": "invoice-standard",
      "name": "Standard Invoice",
      "detailUrl": "./resources/template/invoice-standard.json"
    },
    {
      "type": "theme",
      "key": "corporate",
      "name": "Corporate Theme",
      "detailUrl": "./resources/theme/corporate.json"
    },
    {
      "type": "image",
      "key": "company-logo",
      "name": "Company Logo",
      "detailUrl": "./resources/image/company-logo.json"
    }
  ],
  "dependencies": [
    { "type": "stencil", "catalogKey": "shared-components", "key": "company-header" },
    { "type": "font", "catalogKey": "shared-components", "key": "brand-sans" }
  ]
}
```

| Field                                                         | Type    | Required | Description                                                                                                                                                                                               |
| ------------------------------------------------------------- | ------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `schemaVersion`                                               | integer | yes      | The single catalog-wide wire version — the manifest is authoritative for it and every resource detail echoes the same number (see [Parts & contract versions](#parts--contract-versions)). Currently `8`. |
| `catalog.registry`, `.namespace`, `.key`                      | string  | yes      | The catalog's name: the registry it is published to, the namespace within it, and its key within the namespace. Until wire v8 the key was `slug`, and registry and namespace did not exist.               |
| `catalog.name`                                                | string  | yes      | Display name.                                                                                                                                                                                             |
| `catalog.description`                                         | string  | no       | Description for people.                                                                                                                                                                                   |
| `catalog.attributes`, `.keywords`, `.presentation`            | various | no       | Discovery labels, keywords, and which of its images are the catalog's icon and gallery. Presentation only: the fingerprint does not cover them.                                                           |
| `catalog.license`                                             | object  | no       | The terms the content is licensed under (SPDX expression, URL, copyright text).                                                                                                                           |
| `catalog.defaultTheme`, `.presets`                            | various | no       | The theme a template that names none renders with, and the block style presets the content may use ([theming](../epistola.md#theming)).                                                                   |
| `catalog.quality`                                             | object  | no       | The quality profiles every template uses, and the catalog's overrides of their rules. This installation does not store quality profiles yet and refuses a release that carries any.                       |
| `publisher.name`                                              | string  | yes      | Publisher name; `publisher.url` is optional.                                                                                                                                                              |
| `release.version`                                             | string  | yes      | `MAJOR.MINOR.PATCH`, or `MAJOR.MINOR.PATCH-<namespace>.<n>` for a patch release by someone who does not hold the catalog's namespace. See [`catalog-versioning.md`](../catalog-versioning.md).            |
| `release.fingerprint`                                         | string  | yes      | `v6:` and the lowercase hex SHA-256 of the release's canonical content: the release's identity. Required at wire v8; absent only in a catalog migrated from v7.                                           |
| `release.releasedAt`, `.releaseId`, `.base`                   | various | no       | When it was released, its id, and the release it was cut from.                                                                                                                                            |
| `catalogDependencies`                                         | array   | no       | The catalogs this one depends on, each pinned to one release. Content names a dependency by its `alias`.                                                                                                  |
| `catalogDependencies[].alias`                                 | string  | yes      | How the content names the catalog. A producer uses the catalog's key unless two dependencies share one.                                                                                                   |
| `catalogDependencies[].registry`, `.namespace`, `.catalogKey` | string  | yes      | The dependency's name.                                                                                                                                                                                    |
| `catalogDependencies[].releaseFingerprint`                    | string  | yes      | The pin: the fingerprint of the release this catalog renders with. `releaseVersion` is that release's number, a hint for people.                                                                          |
| `resources`                                                   | array   | yes      | The resource index.                                                                                                                                                                                       |
| `resources[].type`                                            | string  | yes      | `template`, `theme`, `stencil`, `attribute`, `image`, `codeList`, `font`, or `qualityProfile`.                                                                                                            |
| `resources[].key`                                             | string  | yes      | Unique within the catalog and type. Until wire v8 this was `slug`.                                                                                                                                        |
| `resources[].name`                                            | string  | yes      | Display name; `description` and `updatedAt` are optional.                                                                                                                                                 |
| `resources[].detailUrl`                                       | string  | yes      | Path of the resource detail JSON, relative to the manifest.                                                                                                                                               |
| `dependencies`                                                | array   | no       | The resources in other catalogs the content uses, derived from the content on export.                                                                                                                     |
| `dependencies[].type`                                         | string  | yes      | `theme`, `stencil`, `image`, `codeList`, `font`, or `qualityProfile`.                                                                                                                                     |
| `dependencies[].catalogKey`                                   | string  | yes      | The alias `catalogDependencies` declares for the catalog that holds it.                                                                                                                                   |
| `dependencies[].key`                                          | string  | yes      | The resource's key in that catalog.                                                                                                                                                                       |

### Cross-Catalog Dependencies

Content may use resources from the catalogs it depends on. Every such reference names the catalog by its alias in `catalogKey`; a reference with no `catalogKey` is to the catalog's own resource:

- **Themes**: `themeRef` in a template model (`themeKey` with `catalogKey`), and a template's `themeKey` with `themeCatalogKey`
- **Stencils**: a `stencil` node's `props.stencilKey` with `props.catalogKey`
- **Images**: an `image` node's `props.imageKey` with `props.catalogKey`
- **Code lists**: an attribute's `codeListBinding` (`key` with `catalogKey`)
- **Fonts**: `fontFamily` (`key` with `catalogKey`) in a theme's or template's document styles

On export, Epistola scans the content against the catalog's own resources and lists every reference to another catalog in `dependencies`. Since wire v7 every kind is catalog-scoped, images included.

A dependency carries no version: the release it renders with is the one `catalogDependencies` pins. On **import**, every pinned release must already be installed in the tenant, or be carried in the archive; otherwise the import is refused (`CatalogImportDependenciesMissingException`), naming what is missing. An archive exported with _Include the catalogs it uses_ carries them under `dependencies/`, and importing it receives those first ([catalog versioning](../catalog-versioning.md#with-what-it-uses)). Showing the dependencies in the install dialog before installing is [#917](https://github.com/epistola-app/epistola-suite/issues/917).

### Resource Detail Files

Each resource has a detail JSON file (`./resources/{type}/{key}.json`) containing the full resource payload. Every detail echoes the catalog-wide `schemaVersion`, so each file is self-describing:

```json
{
  "schemaVersion": 8,
  "resource": {
    "type": "template",
    "key": "invoice-standard",
    "name": "Standard Invoice",
    "themeKey": "corporate",
    "templateModel": { ... },
    "variants": [ ... ],
    "dataModel": { ... },
    "dataExamples": [ ... ]
  }
}
```

The `type` member selects the resource kind. Beyond `type`, `key`, `name` and the optional `tags` and `metadata` every kind has:

- **template**: `templateModel`, `variants` (each with `key`, `attributes`, `isDefault` and its own `templateModel`), and optionally `description`, `themeKey`/`themeCatalogKey`, `dataModel`, `dataExamples`, `pdfaEnabled`, `quality`
- **theme**: `documentStyles`, `pageSettings`, `blockStylePresets`, `defaultBlockStyles`, `spacingUnit`
- **stencil**: `content` (a template document), and optionally `parameterSchema` and `readsTemplateData`
- **attribute**: `allowedValues` or a `codeListBinding`
- **image**: `mediaType`, `contentHash`, and optionally `width` and `height`
- **codeList**: `entries`
- **font**: `kind` and `variants`
- **qualityProfile**: `rules` and optionally `acceptedWords`. The contract defines it, but this installation refuses a release that carries quality profiles or a quality binding (ADR 0046)

### Asset Binary Content

An image's detail names its binary by hash:

```json
{
  "type": "image",
  "key": "company-logo",
  "name": "Company Logo",
  "mediaType": "image/png",
  "contentHash": "9950f04a086b80b18a7a9ee7a0cc69a6f65739c4b564445c0d584e8ae74d9c20"
}
```

From wire v7 a binary is placed by its hash, at `bin/<contentHash>`, and `contentUrl` is deprecated and optional. An archive migrated from v6 still carries the `contentUrl` it was written with, because a migration rewrites documents and cannot move files; when the field is present it is resolved relative to the manifest URL.

### Catalog and code-list sources

Every read behind a catalog or code list — the manifest, each resource detail, each image and font
binary, a code list's entries, and every redirect in between — goes through `OutboundUrlGuard`
(`catalog/outbound/`). The rules hold at input time (registering a catalog, creating or changing a
code list's URL is a `SOURCE_URL_NOT_ALLOWED` validation error on `sourceUrl`) and again at every
fetch, because a stored URL, or one a fetched manifest names, was never typed by anyone the
installation trusts.

| Scheme       | Allowed                                                                                                        |
| ------------ | -------------------------------------------------------------------------------------------------------------- |
| `https://`   | Always, to a host whose every address is public                                                                |
| `http://`    | Only with `epistola.catalog.allow-http=true` (code lists: `epistola.codelists.allow-http`) — local development |
| `file:///`   | Only for a regular file inside a directory listed in `epistola.catalog.file-roots` (off by default)            |
| `classpath:` | Only Epistola's bundled catalogs, under `classpath:epistola/catalogs/`                                         |

- **Addresses.** A network source is refused when any address its host resolves to is loopback,
  private (RFC 1918), carrier-grade NAT, benchmarking, unique-local or site-local IPv6 — unless
  `epistola.catalog.outbound.allow-private-networks=true` or the host is listed in
  `epistola.catalog.outbound.allowed-hosts`. Link-local (including `169.254.169.254` and
  `fd00:ec2::254`), unspecified (`0.0.0.0`, `::`), multicast, broadcast and documentation addresses
  are refused regardless. IPv4 addresses carried inside IPv6 (mapped, compatible, 6to4, NAT64) are
  judged by the IPv4 address, and numeric hosts in any form other than a dotted quad
  (`2130706433`, `0x7f.1`, `0177.0.0.1`) are refused.
- **Pinned connections.** The HTTP client resolves host names through the guard, so the addresses
  that were checked are the ones that are dialled; a host that changes its answer between the check
  and the connection (DNS rebinding) is refused at connect. TLS verification and SNI still use the
  host name. With a JVM proxy configured (`https.proxyHost`), the proxy resolves the target, so only
  the pre-flight check applies to it.
- **Redirects** are followed by hand, at most `epistola.catalog.outbound.max-redirects` (3), each
  checked as a fresh request; never from `https` to `http` or to another scheme, and
  `Authorization` / `X-API-Key` are dropped when a redirect leaves the original origin.
- **Manifests name resources by relative path only.** A `detailUrl` or `contentUrl` must be a path
  below the manifest's own directory (`./resources/…`, `bin/<hash>`). An absolute URL, a
  protocol-relative `//host/…`, a root-relative `/…`, a `..` segment (also encoded) or a backslash
  is refused, so a manifest cannot send a fetch — or the catalog's credentials — to another host,
  another scheme or outside its own tree.
- **Limits.** Every response and file is capped at `epistola.catalog.max-decompressed-size`
  (20 MB), the same budget a ZIP import has; connect and read time out after 5 and 15 seconds.
- **Errors.** Status failures never carry the response body, and a failed code-list refresh stores a
  generic `last_refresh_error`; the cause is in the server log.

```yaml
epistola:
  catalog:
    allow-http: false
    # Directories file: sources may be read from. Empty: file: sources are off.
    file-roots: []
    outbound:
      allow-private-networks: false
      allowed-hosts: []
      max-redirects: 3
```

The Helm chart exposes these as `catalog.fileRoots`, `catalog.outbound.allowPrivateNetworks` and
`catalog.outbound.allowedHosts` (see [deployment](../deployment.md#catalog-and-code-list-sources)).
Outside the chart, a list can be one comma-separated environment variable, e.g.
`EPISTOLA_CATALOG_FILEROOTS=/srv/catalogs,/mnt/shared`.

`detailUrl` and `contentUrl` are resolved against the manifest URL and must stay below it.

### Authentication

Remote catalogs support three authentication types:

- `NONE` — No authentication
- `API_KEY` — Sent as `X-API-Key` header
- `BEARER` — Sent as `Authorization: Bearer <token>` header

## Import Flow

A catalog installed from its source URL is received exactly as an archive is (contract ADR 0008,
"installing is importing"). `InstallFromCatalog` and `UpgradeCatalog` are thin adapters:

1. `ManifestUrlReleaseSource` fetches the manifest and every resource detail and binary, migrated
   from an older wire version as `CatalogClient` reads them, every fetch through `OutboundUrlGuard`.
   A failed fetch refuses the install before anything is written (a catalog is one install unit,
   [#850](https://github.com/epistola-app/epistola-suite/issues/850)).
2. It writes them into the release's archive, a ZIP, with the publisher's release record.
3. `ImportCatalogZip` receives that archive as any other, through the one path every install takes:
   the fingerprint is recomputed, every binary is checked against its content hash, the releases it
   pins must be present, the resources its content refers to must be carried (the contract
   validator), and it is kept as it says it is or refused.
4. Without changes of its own the next version moves to it, and what the publisher no longer
   publishes is removed unless another catalog uses it (`CatalogUpgradeConflictException`). With
   changes of its own the release is recorded beside the next version, to be merged (ADR 0032).

The import runs within `CatalogImportContext.runAsImport {}` to bypass editability checks on the catalog it installs into.

### Wire-format version gate

Every part (the manifest and each resource detail) is gated by the **single catalog-wide** `schemaVersion` before it is bound. The manifest is authoritative for the version, and every resource detail **must** carry the same number — a detail whose stamp differs from the manifest's is rejected. The portable `CatalogSchemaMigrator` decides:

| `schemaVersion`                                       | Behaviour                                                                             |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `== 8`                                                | Bind to the current portable model.                                                   |
| `4`–`7`                                               | Migrate step by step to `8`, then bind.                                               |
| `< 4`                                                 | **Reject** as too old. Re-export from a current producer; there is no migration path. |
| `> 8`                                                 | **Reject** as too new. Upgrade this instance.                                         |
| detail version differs from manifest                  | **Reject** because a catalog has one wire version.                                    |
| valid version but shape fails to bind                 | **Reject** as an unrecognized current-version payload.                                |
| invalid JSON, missing version, or non-integer version | **Reject** as an unrecognized catalog payload.                                        |

The gate runs at both import chokepoints (the ZIP path and `CatalogClient`), so
browse, preview, and upgrade checks use the same policy. See
[catalog-versioning.md](../catalog-versioning.md#fingerprint-algorithm) for
fingerprint compatibility.

### Import action for a below-current payload

The Suite ZIP path has a product-level decision point for portable migrations,
because a ZIP is a source-less, one-shot transport. Suite applies these rules
after portable migration and before database mutation
([`CatalogImportSchemaAction`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogImportSchemaAction.kt)):

| Case (payload `schemaVersion` below current)     | Action                                                                                                                                                                                                         |
| ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Below current, **no** migration path             | `BLOCK_TOO_OLD` → `CatalogSchemaTooOldException`. Nothing can bring it to current — re-export from a current source.                                                                                           |
| Below current, **migratable**, not yet confirmed | `CONFIRM_MIGRATION` → `CatalogMigrationConfirmationRequiredException`. Migrating **mutates** the imported content, so the operator confirms first. Not a `CatalogSchemaException` — a prompt, not a rejection. |
| Below current, **migratable**, confirmed         | `IMPORT` — the `ImportCatalogZip` command was re-issued with `confirmMigration = true`.                                                                                                                        |

This decision is the same for every import, whichever catalog receives it; installs nobody is asked about (REST, Exchange, a registry) confirm up front. It applies only to archives. URL subscriptions re-fetch their
source and surface the schema sync state below instead.

### Source schema sync

For a catalog whose source is a URL, [`CheckCatalogUpgrade`](../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/CheckCatalogUpgrade.kt) (the cheap, manifest-only "is an upgrade available?" check behind the list's per-row **Check for updates**) also reports how the source's wire `schemaVersion` compares with this instance's `CATALOG_SCHEMA_VERSION`, as a `CatalogSchemaSyncState`:

| `CatalogSchemaSyncState` | Meaning                                                               | List badge                                                                                               |
| ------------------------ | --------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `IN_SYNC`                | source and instance at the same wire version                          | normal up-to-date / update-available state                                                               |
| `SOURCE_BEHIND`          | source publishes an **older** wire version than this instance         | amber **"out of sync"** — the source must republish at the current version                               |
| `SOURCE_AHEAD`           | source publishes a **newer** wire version than this instance can read | **"upgrade Epistola"** (also reached when `fetchMigratedManifest` throws `CatalogSchemaTooNewException`) |

The schema mismatch takes **priority** over the release-version upgrade state — `SOURCE_BEHIND`/`SOURCE_AHEAD` are shown instead of "update available", because a mirror is never migrated locally. Nothing is persisted: the source version is read live during the existing on-demand check and compared against the app's current version.

**Operator surfaces for a rejected, confirmable, or out-of-sync payload:**

- **Web UI** (import dialog): a **rejection** renders inline in the dialog (its `alert-error` slot) with the actionable remediation — upgrade this instance, or re-export from a current source. A **confirmable** migration renders an inline "Update available — Import & update to v*N*" prompt (`import-migration-confirm`) whose button re-submits the same form with `confirmMigration=true`. A source's **out-of-sync** state shows as the amber / "upgrade Epistola" badge in the catalog list's Version cell (`version-status` fragment).
- **REST** (`POST /api/tenants/{id}/catalogs/import`): non-interactive, so it sets `confirmMigration = true` implicitly — a migratable-old import migrates without a round-trip. A **rejection** is an [RFC 9457](../adr/0004-rfc7807-problem-details.md) problem with a dedicated `type` — `…/errors/catalog-schema-too-new` (extensions `version`, `supportedVersion`), `…/catalog-schema-too-old` (`version`, `baselineVersion`), or `…/catalog-schema-unknown` — all `400`.
- **MCP**: catalog import is not an MCP surface.

### Dependency Resolution

`DependencyScanner` scans content for references to other resources, through the reference sites listed under [Cross-Catalog Dependencies](#cross-catalog-dependencies): themes, stencils, images, code lists and fonts, plus the attribute keys a variant uses.

`DependencyResolver`, behind the install preview, takes selected resources and the full manifest, then iteratively adds missing dependencies until the set is complete. It validates that all dependencies exist in the manifest.

## Export

### ZIP Export

`ExportCatalogZip` exports one release of a catalog as a self-contained ZIP:

```
catalog.json
resources/
  template/hello-world.json
  theme/corporate.json
  stencil/company-header.json
  attribute/language.json
  image/company-logo.json
bin/<contentHash>        (image and font binaries)
dependencies/…           (only with "Include the catalogs it uses")
```

The ZIP structure matches the protocol layout, so it can be served as a static catalog. Available via `GET /tenants/{tenantId}/catalogs/{catalogId}/export`, with `version` naming the release (the highest when omitted) and `includeDependencies=true` to carry the releases it pins.

An export carries a release exactly as it was released, whatever the drafts have done since; a catalog with no release has nothing to export (see [`catalog-versioning.md`](../catalog-versioning.md#exporting-a-release)). Stencils travel by reference: 2.0 has no stencil versions to preserve ([version-axes.md](../version-axes.md)).

Cross-catalog dependencies are automatically detected during export by scanning the content against the catalog's own resource list. Any external reference is added to the `dependencies` field in `catalog.json`.

Configurable size limits protect against oversized exports:

- `epistola.catalog.max-zip-size`: Maximum compressed ZIP size (default 10MB)
- `epistola.catalog.max-decompressed-size`: Maximum decompressed size (default 20MB)

### ZIP Import

`ImportCatalogZip` receives the release a ZIP archive carries (see
[`catalog-versioning.md`](../catalog-versioning.md#importing-installs-a-release)):

- If the catalog is new: it is created as the archive names it, holding the release
- If the tenant has that release with the same fingerprint: nothing changes; other content is refused
- If the release is lower than the catalog's highest, or its next version has changes of its own: the release is recorded beside the others
- Otherwise its next version moves to the release

Available via:

- UI: `POST /tenants/{tenantId}/catalogs/import` (multipart form)
- REST API: `POST /api/tenants/{tenantId}/catalogs/import` (multipart, API key auth)

The REST API endpoint is used by the Valtimo plugin for catalog sync on startup.

## UI

### Catalog List (`/tenants/{tenantId}/catalogs`)

- Create catalog (dialog)
- Register a catalog from a manifest URL (dialog with URL, auth type, credential); refused when the tenant has a catalog under its key
- Browse catalog resources
- Export catalog as ZIP (all catalogs)
- Delete catalog (all except default, with confirmation)

### Catalog Browse (`/tenants/{tenantId}/catalogs/{catalogId}/browse`)

- List all resources with type badges
- Install individual resources or all resources
- Dependency resolution preview (shows auto-included dependencies)

### Resource Pages

All resource list pages (templates, themes, stencils, attributes, assets) have:

- Catalog filter dropdown
- Catalog column in tables
- Catalog selector in create forms
- `/{catalogId}/{resourceId}` URL patterns for detail pages

Resources in a catalog that cannot be changed here show as read-only with disabled edit/delete controls.
