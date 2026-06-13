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
  "schemaVersion": 3,
  "resource": {
    "type": "attribute",
    "slug": "country",
    "name": "Country",
    "codeListBinding": { "slug": "iso-3166-1-alpha2" }
  }
}
```

## Fields (`resource`)

| Field             | Type   | Required | Description                                                                                                                                                   |
| ----------------- | ------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`            | string | yes      | Discriminator: `"attribute"`.                                                                                                                                 |
| `slug`            | string | yes      | Unique within the catalog.                                                                                                                                    |
| `name`            | string | yes      | Display name.                                                                                                                                                 |
| `codeListBinding` | object | no       | `{ slug, catalogKey? }` — binds allowed values to a [code list](../../code-list/v3/contract.md). A cross-catalog binding surfaces as a manifest `dependency`. |

## Validation / behaviour

- On import the bound code list must already exist (install order guarantees code lists install first); a cross-catalog binding must resolve or the import is rejected.

## Changed in v3

- **Replaced inline `allowedValues` with a `codeListBinding`** reference. Earlier versions of this contract carried the enumeration inline on the attribute (`allowedValues`); v3 normalises values into a shared, reusable [code-list](../../code-list/v3/contract.md) resource and binds to it. This is the canonical example of a per-part contract change — diff this folder against a future `v4/` to see the next one.

## Related parts

- Binds to [Code list](../../code-list/v3/contract.md); selected by [Template](../../template/v2/contract.md) `variants[].attributes`.
