# Stencil resource contract — v1

> Part of [catalog import/export](../../../README.md). **Superseded** — upgraded to [v2](../v2/contract.md) on import. [All stencil versions](../).

The original stencil wire shape, used by `epistola-model < 0.6.0`. It carried the stencil's **latest published content only**, with **no version number** — on import the target assigned the next free version (`MAX(target) + 1`).

**Upgraded by:** [`StencilV1ToV2RequireVersionMigration`](../../../../../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/migrations/steps/StencilV1ToV2RequireVersionMigration.kt) — a v1 detail is migrated to [v2](../v2/contract.md) by assigning `version = 1` (the old wire expressed exactly one published version's content) before it is bound and installed. A producer at v1 need not be re-run.

## Shape

```json
{
  "schemaVersion": 1,
  "resource": {
    "type": "stencil",
    "slug": "company-header",
    "name": "Company Header",
    "description": "Branded header with logo and address.",
    "tags": ["header", "branding"],
    "content": { "modelVersion": 1, "root": "n-root", "nodes": {}, "slots": {} }
  }
}
```

Identical to [v2](../v2/contract.md) except there is **no `version` field** on `resource`.

## Migrated to v2

- `resource.version` is assigned **1** when absent (idempotent — a detail that already carries a `version` is untouched). See [ADR 0003](../../../../adr/0003-stencil-version-in-export.md) (why the pin exists) and [ADR 0006](../../../../adr/0006-catalog-wire-format-migrations.md) (the per-part migration mechanism).
