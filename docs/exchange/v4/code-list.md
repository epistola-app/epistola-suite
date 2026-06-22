# Code-list — catalog wire v4

> Part of the [catalog **v4** wire format](README.md) ([exchange overview](../README.md)).

**Role:** a reusable enumeration (e.g. BCP-47 languages, ISO-3166 countries) that [attributes](attribute.md) (and data-contract bindings) draw their allowed values from.

**DTO:** `CodeListResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) (`ExportCodeLists`).
**Imported by:** [`InstallFromCatalog`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
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

- A code list must install **before** any [attribute](attribute.md) that binds to it (guaranteed by install order 1 < 3).

## Shape history

- Code lists became the canonical home for enumerations when the [attribute](attribute.md) shape moved from inline `allowedValues` to a `codeListBinding`. This is the history of this resource's shape; on the wire it carries the catalog-wide `schemaVersion` (currently `4`), not an independent code-list version.

## Related parts

- Bound by [Attribute](attribute.md) (`codeListBinding`).
