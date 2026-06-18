# Code-list resource contract — v3

> Part of [catalog import/export](../../../README.md). **Current** code-list version. [All code-list versions](../).

**Role:** a reusable enumeration (e.g. BCP-47 languages, ISO-3166 countries) that [attributes](../../attribute/v3/contract.md) (and data-contract bindings) draw their allowed values from.

**DTO:** `CodeListResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) (`ExportCodeLists`).
**Imported by:** [`InstallFromCatalog`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 1 (early — attributes bind to it).
**Wire location:** `./resources/codeList/{slug}.json`.

## Shape

```json
{
  "schemaVersion": 4,
  "resource": {
    "type": "codeList",
    "slug": "bcp-47",
    "name": "BCP-47 Language Tags",
    "description": "Curated subset of common BCP-47 language + region tags.",
    "entries": [
      { "code": "ar-AE", "label": "Arabic (United Arab Emirates)", "sortOrder": 10 },
      { "code": "ar-EG", "label": "Arabic (Egypt)", "sortOrder": 11 }
    ]
  }
}
```

## Fields (`resource`)

| Field                 | Type    | Required | Description                                                                                   |
| --------------------- | ------- | -------- | --------------------------------------------------------------------------------------------- |
| `type`                | string  | yes      | Discriminator: `"codeList"` (note the camelCase wire spelling; the directory is `codeList/`). |
| `slug`                | string  | yes      | Unique within the catalog.                                                                    |
| `name`                | string  | yes      | Display name.                                                                                 |
| `description`         | string  | no       | Free text.                                                                                    |
| `entries[]`           | array   | yes      | The enumeration.                                                                              |
| `entries[].code`      | string  | yes      | Stored value (e.g. `nl-NL`).                                                                  |
| `entries[].label`     | string  | yes      | Human label.                                                                                  |
| `entries[].sortOrder` | integer | no       | Display ordering.                                                                             |

## Validation / behaviour

- A code list must install **before** any [attribute](../../attribute/v3/contract.md) that binds to it (guaranteed by install order 1 < 3).

## Changed in v3

- Baseline documented version. Code lists became the canonical home for enumerations when [attribute v3](../../attribute/v3/contract.md) moved from inline `allowedValues` to a `codeListBinding`.

## Related parts

- Bound by [Attribute](../../attribute/v3/contract.md) (`codeListBinding`).
