# Attribute resource contract — v3

> Part of [catalog import/export](../../../README.md). **Current** attribute version. [All attribute versions](../).

**Role:** a variant-attribute definition (e.g. _Country_, _Language_) used to drive template variants. Its allowed values come from a bound [code list](../../code-list/v3/contract.md).

**DTO:** `AttributeResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt).
**Imported by:** [`InstallFromCatalog`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 3 (after the code list it binds to).
**Wire location:** `./resources/attribute/{slug}.json`.

## Shape

```json
{
  "schemaVersion": 5,
  "resource": {
    "type": "attribute",
    "slug": "country",
    "name": "Country",
    "allowedValues": [],
    "codeListBinding": { "slug": "iso-3166-1-alpha2" }
  }
}
```

## Fields (`resource`)

| Field             | Type           | Required | Description                                                                                                                                                   |
| ----------------- | -------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`            | string         | yes      | Discriminator: `"attribute"`.                                                                                                                                 |
| `slug`            | string         | yes      | Unique within the catalog.                                                                                                                                    |
| `name`            | string         | yes      | Display name.                                                                                                                                                 |
| `allowedValues`   | array<string\> | no       | Inline enumeration of permitted values (may be empty). Round-tripped as-is by export/import; superseded by `codeListBinding` when a binding is present.       |
| `codeListBinding` | object         | no       | `{ slug, catalogKey? }` — binds allowed values to a [code list](../../code-list/v3/contract.md). A cross-catalog binding surfaces as a manifest `dependency`. |

## Validation / behaviour

- On import the bound code list must already exist (install order guarantees code lists install first); a cross-catalog binding must resolve or the import is rejected.

## Changed in v3

- **Added `codeListBinding`** as the preferred way to source allowed values. Earlier versions carried the enumeration only inline (`allowedValues`); v3 lets an attribute instead bind to a shared, reusable [code-list](../../code-list/v3/contract.md) resource. `allowedValues` is **not removed** — the wire model still carries and round-trips it (export emits it, import consumes it), so both can be present; a binding, when set, supersedes the inline list. This is the canonical example of a resource-shape contract change — diff this folder against a future `v4/` to see the next one. (The `vN` is this doc's shape history; on the wire the detail carries the catalog-wide `schemaVersion`, not an independent attribute version.)

## Related parts

- Binds to [Code list](../../code-list/v3/contract.md); selected by [Template](../../template/v2/contract.md) `variants[].attributes`.
