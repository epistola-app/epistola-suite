# Template resource contract — v2

> Part of [catalog import/export](../../../README.md). **Current** template version. [All template versions](../).

**Role:** a document template — its layout (`templateModel`), the data shape it expects (`dataModel` JSON Schema), sample data, and its variants.

**DTO:** `TemplateResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) → wrapped in `ResourceDetail` by [`CatalogContentBuilder`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogContentBuilder.kt).
**Imported by:** [`InstallFromCatalog`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 6 (last — depends on theme/stencil/asset).
**Wire location:** `./resources/template/{slug}.json`.

## Shape

```json
{
  "schemaVersion": 5,
  "resource": {
    "type": "template",
    "slug": "simple-letter",
    "name": "Simple Letter",
    "themeId": "corporate",
    "dataModel": { "type": "object", "properties": { "…": {} } },
    "dataExamples": [{ "name": "Sample", "data": { "…": "…" } }],
    "templateModel": { "modelVersion": 1, "root": "n-root", "nodes": {}, "slots": {} },
    "variants": [
      {
        "id": "default",
        "title": "Default",
        "attributes": {},
        "templateModel": null,
        "isDefault": true
      }
    ]
  }
}
```

## Fields (`resource`)

| Field                      | Type                 | Required | Description                                                                                                                                                                                                         |
| -------------------------- | -------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`                     | string               | yes      | Discriminator: `"template"`.                                                                                                                                                                                        |
| `slug`                     | string               | yes      | Unique within the catalog.                                                                                                                                                                                          |
| `name`                     | string               | yes      | Display name.                                                                                                                                                                                                       |
| `themeId`                  | string               | yes      | Slug of the theme this template renders with. Same-catalog by default; a cross-catalog theme is also recorded as a manifest `dependency`.                                                                           |
| `dataModel`                | object (JSON Schema) | yes      | The data contract the template binds against.                                                                                                                                                                       |
| `dataExamples`             | array                | no       | `{ name, data }` sample payloads used for preview/testing.                                                                                                                                                          |
| `templateModel`            | object               | yes      | The editor document model (`modelVersion`, `root`, `nodes`, `slots`, optional `pageSettingsOverride`, `themeRef`). The node/slot graph itself is **not** versioned by this contract — see `modelVersion` inside it. |
| `variants[]`               | array                | yes      | Per-variant overrides.                                                                                                                                                                                              |
| `variants[].id`            | string               | yes      | Variant id (e.g. `default`, `en-us`).                                                                                                                                                                               |
| `variants[].title`         | string               | yes      | Display title.                                                                                                                                                                                                      |
| `variants[].attributes`    | object               | yes      | Attribute → value map selecting this variant (e.g. `{ "system.locale": "nl-NL" }`).                                                                                                                                 |
| `variants[].templateModel` | object \| null       | no       | Variant-specific model override; `null` = inherit the base `templateModel`.                                                                                                                                         |
| `variants[].isDefault`     | boolean              | yes      | Exactly one variant is the default.                                                                                                                                                                                 |

## Validation / behaviour

- On import the `themeId` (and any cross-catalog `themeRef`/stencil/asset references) must resolve, or the import is rejected (see manifest `dependencies`).
- `dataModel` is JSON-Schema-validated against bound data at generation time, not at import.

## Changed in v2

- Baseline documented version. (`templateModel` is the editor model; its own evolution is tracked by the embedded `modelVersion`, independent of this wire contract.)

## Related parts

- [Theme](../../theme/v2/contract.md) (via `themeId`), [Stencil](../../stencil/v2/contract.md) & [Asset](../../asset/v2/contract.md) (referenced from `templateModel`).
- [Manifest](../../../manifest/v4/contract.md) — cross-catalog refs surface as `dependencies`.
