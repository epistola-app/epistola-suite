# Stencil resource contract — v2

> Part of [catalog import/export](../../../README.md). **Current** stencil version. [All stencil versions](../).

**Role:** a reusable, parameterised document fragment (e.g. a company header) that templates embed. Stencils are **published** — the carried content is pinned to a published version number.

**DTO:** `StencilResource` (`CatalogResource` subtype, `epistola-model`).
**Exported by:** [`ExportResources.kt`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/ExportResources.kt) — selects the **latest published** stencil version and stamps its number into `version`.
**Imported by:** [`InstallFromCatalog`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/InstallFromCatalog.kt); conflict handling in [`ImportCatalogZip`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/ImportCatalogZip.kt).
**Install order:** 5 (before templates; after theme/asset).
**Wire location:** `./resources/stencil/{slug}.json`.

## Shape

```json
{
  "schemaVersion": 2,
  "resource": {
    "type": "stencil",
    "slug": "company-header",
    "name": "Company Header",
    "version": 1,
    "description": "Branded header with logo and address.",
    "tags": ["header", "branding"],
    "content": { "modelVersion": 1, "root": "n-root", "nodes": {}, "slots": {} }
  }
}
```

## Fields (`resource`)

| Field         | Type            | Required | Description                                                                                                                                                                                 |
| ------------- | --------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `type`        | string          | yes      | Discriminator: `"stencil"`.                                                                                                                                                                 |
| `slug`        | string          | yes      | Unique within the catalog.                                                                                                                                                                  |
| `name`        | string          | yes      | Display name.                                                                                                                                                                               |
| `version`     | integer         | **yes**  | The **published version number** of the carried `content`. Used to pin and to detect conflicts. Added in this contract — see [ADR 0003](../../../../adr/0003-stencil-version-in-export.md). |
| `description` | string          | no       | Free text.                                                                                                                                                                                  |
| `tags`        | array of string | no       | Discovery tags.                                                                                                                                                                             |
| `content`     | object          | yes      | The stencil's `TemplateDocument` (same node/slot model as a template, with its own `modelVersion`).                                                                                         |

## Validation / behaviour

- On import, if `(stencil slug, version)` already exists in the target with **different** content, the import raises `StencilVersionImportConflictsException`.
- Resolution options (AUTHORED MERGE only): **FAIL** (default) or **RENUMBER** (assign the next free version). See [ADR 0003](../../../../adr/0003-stencil-version-in-export.md) and the README's [Stencil version conflict handling](../../../README.md).

## Changed in v2

- Added the required `version` field (published-version pin) per [ADR 0003](../../../../adr/0003-stencil-version-in-export.md) — landed with `epistola-model:0.6.0`. Prior shape carried `content` with no version pin.

## Related parts

- Embedded by [Template](../../template/v2/contract.md) (via `node.props.catalogKey`); cross-catalog use surfaces as a [Manifest](../../../manifest/v4/contract.md) `dependency` (`type: stencil`).
