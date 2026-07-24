# Theme — catalog wire v4

> Part of the [catalog **v4** wire format](README.md) ([exchange overview](../README.md)).

**Role:** the visual styling a template renders with — document-level typography/colour, page settings, and named block-style presets.

**DTO:** `ThemeResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt).
**Imported by:** [`InstallFromCatalog`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 4 (before stencils/templates that reference it).
**Wire location:** `./resources/theme/{slug}.json`.

## Shape

```json
{
  "schemaVersion": 4,
  "resource": {
    "type": "theme",
    "slug": "default",
    "name": "Default",
    "description": "Minimal default theme shipped with Epistola.",
    "documentStyles": {
      "fontFamily": { "slug": "inter", "catalogKey": "system" },
      "fontSize": "11pt",
      "lineHeight": 1.5,
      "color": "#333333"
    },
    "pageSettings": {
      "format": "A4",
      "orientation": "portrait",
      "margins": { "top": 20, "right": 20, "bottom": 20, "left": 20 }
    },
    "blockStylePresets": { "muted": { "label": "Muted Text", "styles": { "color": "#666666" } } },
    "spacingUnit": 4
  }
}
```

## Fields (`resource`)

| Field               | Type   | Required | Description                                                                                                                                                                         |
| ------------------- | ------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`              | string | yes      | Discriminator: `"theme"`.                                                                                                                                                           |
| `slug`              | string | yes      | Unique within the catalog.                                                                                                                                                          |
| `name`              | string | yes      | Display name.                                                                                                                                                                       |
| `description`       | string | no       | Free text.                                                                                                                                                                          |
| `documentStyles`    | object | yes      | Inheritable document defaults: `fontFamily` (a structured `{ slug, catalogKey? }` ref — a cross-catalog font becomes a manifest `dependency`), `fontSize`, `lineHeight`, `color`, … |
| `pageSettings`      | object | yes      | `format` (e.g. `A4`), `orientation`, `margins { top,right,bottom,left }` (mm).                                                                                                      |
| `blockStylePresets` | object | no       | Named presets (`{ label, styles }`) referenced by template nodes via `stylePreset`.                                                                                                 |
| `spacingUnit`       | number | no       | Base spacing unit (pt) for `sp` units. Defaults applied when absent.                                                                                                                |

## Validation / behaviour

- A `documentStyles.fontFamily` referencing another catalog is recorded as a manifest `dependency` (type `font` resolution is tenant/catalog-scoped) and must resolve on import.

## Shape history

- `documentStyles.fontFamily` is the structured `{ slug, catalogKey? }` font reference (not a CSS stack). This is the history of this resource's shape; on the wire it carries the catalog-wide `schemaVersion` (currently `4`), not an independent theme version.

## Related parts

- Referenced by [Template](template.md) via `themeId`; may depend on a [Font](font.md).
- [Manifest](manifest.md) `dependencies` for cross-catalog font refs.
