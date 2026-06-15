# Font resource contract — v1

> Part of [catalog import/export](../../../README.md). **Current** font version. [All font versions](../).

**Role:** a customer-authored font family and its asset-backed faces (variants). Only **asset-backed** fonts are exchanged — the bundled `CLASSPATH` system fonts (Inter, Roboto, …) are part of the runtime, never exported.

**DTO:** `FontResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt` → `ExportFonts`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) — filters `font_variants.source = 'ASSET'` (system faces excluded).
**Imported by:** [`InstallFromCatalog`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 2 (after the assets its faces point at; before themes that reference it).
**Wire location:** `./resources/font/{slug}.json`.

> **No bundled example.** The demo/system catalogs ship no asset-backed fonts, so there is no committed fixture for this part. Its contract is documented here as the **v1** baseline (no prior shape change is known). See [docs/fonts.md](../../../../fonts.md) for the font model itself. (Exports stamp this `schemaVersion` with the font part's own current version — `CatalogContentBuilder` applies per-part stamps, [ADR 0006](../../../../adr/0006-catalog-wire-format-migrations.md).)

## Shape

```json
{
  "schemaVersion": 1,
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

| Field                  | Type          | Required | Description                                                                         |
| ---------------------- | ------------- | -------- | ----------------------------------------------------------------------------------- |
| `type`                 | string        | yes      | Discriminator: `"font"`.                                                            |
| `slug`                 | string        | yes      | Family slug, unique within the catalog.                                             |
| `name`                 | string        | yes      | Display name.                                                                       |
| `kind`                 | string        | yes      | Family classification (e.g. `sans-serif`, `serif`, `mono`).                         |
| `variants[]`           | array         | yes      | The faces.                                                                          |
| `variants[].weight`    | integer       | yes      | CSS numeric weight (1–1000).                                                        |
| `variants[].italic`    | boolean       | yes      | Italic flag.                                                                        |
| `variants[].assetSlug` | string (UUID) | yes      | The [asset](../../asset/v2/contract.md) (TTF/OTF binary) holding this face's bytes. |

## Validation / behaviour

- Each `variants[].assetSlug` must resolve to an installed [asset](../../asset/v2/contract.md) (install order 0 < 2 guarantees assets land first).
- System (`CLASSPATH`) faces are never serialized — round-tripping a catalog never drags the runtime fonts along.

## Changed in v1

- Documented baseline (no prior committed fixture).

## Related parts

- Faces reference [Assets](../../asset/v2/contract.md); referenced by [Theme](../../theme/v2/contract.md) `documentStyles.fontFamily`. Full model: [docs/fonts.md](../../../../fonts.md).
