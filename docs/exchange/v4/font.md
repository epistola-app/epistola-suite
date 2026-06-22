# Font — catalog wire v4

> Part of the [catalog **v4** wire format](README.md) ([exchange overview](../README.md)).

**Role:** a customer-authored font family and its asset-backed faces (variants). Only **asset-backed** fonts are exchanged — the bundled `CLASSPATH` system fonts (Inter, Roboto, …) are part of the runtime, never exported.

**DTO:** `FontResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt` → `ExportFonts`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) — filters `font_variants.source = 'ASSET'` (system faces excluded).
**Imported by:** [`InstallFromCatalog`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 2 (after the assets its faces point at; before themes that reference it).
**Wire location:** `./resources/font/{slug}.json`.

> **No bundled example.** The demo/system catalogs ship no asset-backed fonts, so there is no committed fixture for this part. Its shape is documented here from the model (no prior shape change is known). See [docs/fonts.md](../../fonts.md) for the font model itself. (On the wire the detail carries the single catalog-wide `schemaVersion` — `CatalogContentBuilder` stamps it uniformly across the manifest and every detail, [ADR 0007](../../adr/0007-catalog-wire-format-migrations.md).)

## Shape

```json
{
  "schemaVersion": 4,
  "resource": {
    "type": "font",
    "slug": "acme-display",
    "name": "Acme Display",
    "kind": "sans-serif",
    "variants": [
      { "weight": 400, "italic": false, "assetSlug": "01966a00-0000-7000-8000-0000000000aa" },
      { "weight": 700, "italic": false, "assetSlug": "01966a00-0000-7000-8000-0000000000ab" }
    ]
  }
}
```

## Fields (`resource`)

| Field                  | Type          | Required | Description                                                       |
| ---------------------- | ------------- | -------- | ----------------------------------------------------------------- |
| `type`                 | string        | yes      | Discriminator: `"font"`.                                          |
| `slug`                 | string        | yes      | Family slug, unique within the catalog.                           |
| `name`                 | string        | yes      | Display name.                                                     |
| `kind`                 | string        | yes      | Family classification (e.g. `sans-serif`, `serif`, `mono`).       |
| `variants[]`           | array         | yes      | The faces.                                                        |
| `variants[].weight`    | integer       | yes      | CSS numeric weight (1–1000).                                      |
| `variants[].italic`    | boolean       | yes      | Italic flag.                                                      |
| `variants[].assetSlug` | string (UUID) | yes      | The [asset](asset.md) (TTF/OTF binary) holding this face's bytes. |

## Validation / behaviour

- Each `variants[].assetSlug` must resolve to an installed [asset](asset.md) (install order 0 < 2 guarantees assets land first).
- System (`CLASSPATH`) faces are never serialized — round-tripping a catalog never drags the runtime fonts along.

## Shape history

- No shape change is known for this resource (no prior committed fixture). On the wire it carries the catalog-wide `schemaVersion` (currently `4`), not an independent font version.

## Related parts

- Faces reference [Assets](asset.md); referenced by [Theme](theme.md) `documentStyles.fontFamily`. Full model: [docs/fonts.md](../../fonts.md).
