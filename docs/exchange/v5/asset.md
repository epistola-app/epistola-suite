# Asset — catalog wire v5

> Part of the [catalog **v4** wire format](README.md) ([exchange overview](../README.md)).

**Role:** a binary image (logo, picture) used by templates/themes. The detail file is **metadata only**; the bytes live in a separate binary file referenced by `contentUrl`.

**DTO:** `AssetResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportAssets.kt`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportAssets.kt) (metadata) + the binary is added to the archive.
**Imported by:** [`InstallFromCatalog`](../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt).
**Install order:** 0 (first — everything else may reference assets).
**Wire location:** `./resources/asset/{slug}.json` (metadata) + the binary at `contentUrl`.

## Shape

```json
{
  "schemaVersion": 5,
  "resource": {
    "type": "asset",
    "slug": "01966a00-0000-7000-8000-000000000001",
    "name": "Epistola Logo",
    "mediaType": "image/svg+xml",
    "width": null,
    "height": null,
    "contentUrl": "./binaries/epistola-logo.svg"
  }
}
```

## Fields (`resource`)

| Field        | Type            | Required | Description                                                                                                                                                                                                                    |
| ------------ | --------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `type`       | string          | yes      | Discriminator: `"asset"`.                                                                                                                                                                                                      |
| `slug`       | string (UUID)   | yes      | Asset id. **Tenant-global** — assets are not catalog-scoped, so asset dependencies carry no `catalogKey`.                                                                                                                      |
| `name`       | string          | yes      | Display name.                                                                                                                                                                                                                  |
| `mediaType`  | string          | yes      | One of the seeded `asset_types` (e.g. `image/png`, `image/jpeg`, `image/webp`, `image/svg+xml`). New media types are added by inserting an `asset_types` row, not by widening an enum — see [`docs/fonts.md`](../../fonts.md). |
| `width`      | integer \| null | no       | Intrinsic pixel width; `null` for vector (SVG).                                                                                                                                                                                |
| `height`     | integer \| null | no       | Intrinsic pixel height; `null` for vector.                                                                                                                                                                                     |
| `contentUrl` | string          | yes      | Relative path to the binary. Resolved against the manifest URL; `..` rejected. In a ZIP, the file is stored at that path; for HTTP-served catalogs it is an endpoint.                                                          |

## Validation / behaviour

- The binary's bytes fold into the catalog **fingerprint** via their own SHA-256, so a changed image changes the catalog identity even though the metadata file is unchanged.
- Path traversal in `contentUrl` is rejected (`CatalogClient`).

## Shape history

- This resource's shape has been stable since it was first documented. It carries the catalog-wide `schemaVersion` (currently `5`), not an independent asset version.

## Related parts

- Referenced by [Template](template.md) (image nodes), [Theme](theme.md), and [Font](font.md) (each font variant points at an asset). Asset dependencies in the [Manifest](manifest.md) are tenant-global (no `catalogKey`).
