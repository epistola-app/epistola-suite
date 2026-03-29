# Explicit Default Variant Flag

## Context

Default variants are currently determined implicitly by having empty attributes (`{}`). This is fragile: there's no DB enforcement of exactly one default per template, default variants can't have attributes, and the convention is not obvious. This change adds an explicit `is_default` boolean column to decouple the default concept from attributes.

**Depends on**: [Tenant-Scoped Composite Primary Keys](./tenant_scoped.md) (must be implemented first)

## Design Decisions

- **Deletion**: Block deletion of the default variant — user must reassign default first
- **Attributes**: Default variant can have any attributes (decoupled from empty attributes)
- **First variant**: Automatically becomes the default
- **Enforcement**: Partial unique index `WHERE is_default = TRUE` in PostgreSQL
- **In-place migration**: Modify V3 directly (no new migration file)

---

## Step 1: DB Migration (V3 in-place)

**Modify**: `modules/epistola-core/src/main/resources/db/migration/V3__template_variants_versions.sql`

Add to the `template_variants` table definition:

```sql
is_default BOOLEAN NOT NULL DEFAULT FALSE,
```

Add partial unique index after the table:

```sql
CREATE UNIQUE INDEX idx_one_default_variant_per_template
    ON template_variants (tenant_key, template_key) WHERE is_default = TRUE;
```

This enforces exactly one default variant per template at the database level.

---

## Step 2: Domain Model

**Modify**: `modules/epistola-core/.../templates/model/TemplateVariant.kt`

- Add `val isDefault: Boolean` to `TemplateVariant`
- Add `val isDefault: Boolean` to `VariantSummary`

---

## Step 3: Query Updates

All queries selecting from `template_variants` must include `is_default`.

| File                                                          | Change                        |
| ------------------------------------------------------------- | ----------------------------- |
| `queries/variants/ListVariants.kt`                            | Add `tv.is_default` to SELECT |
| `queries/variants/GetVariant.kt` (GetVariantHandler)          | Add `tv.is_default` to SELECT |
| `queries/variants/GetVariant.kt` (GetVariantSummariesHandler) | Add `tv.is_default` to SELECT |

---

## Step 4: Command Updates

### CreateDocumentTemplate — mark auto-created variant as default

**Modify**: `modules/epistola-core/.../templates/commands/CreateDocumentTemplate.kt`

- Add `is_default` to INSERT, set `TRUE`

### CreateVariant — auto-default if first variant

**Modify**: `modules/epistola-core/.../templates/commands/variants/CreateVariant.kt`

- Before INSERT: `SELECT COUNT(*) FROM template_variants WHERE tenant_key = :tenantId AND template_key = :templateId`
- If count is 0, set `is_default = TRUE`; otherwise `FALSE`
- Add `is_default` to INSERT SQL

### DeleteVariant — block if default

**Modify**: `modules/epistola-core/.../templates/commands/variants/DeleteVariant.kt`

- After existence check: query `is_default`
- If true, throw `DefaultVariantDeletionException`

### New: SetDefaultVariant command

**Create**: `modules/epistola-core/.../templates/commands/variants/SetDefaultVariant.kt`

```kotlin
data class SetDefaultVariant(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Command<TemplateVariant?>
```

Handler (in transaction):

1. Verify variant belongs to template owned by tenant
2. `UPDATE SET is_default = FALSE WHERE tenant_key = :tenantId AND template_key = :templateId AND is_default = TRUE`
3. `UPDATE SET is_default = TRUE WHERE tenant_key = :tenantId AND id = :variantId RETURNING *`

Returns null if variant not found.

### New: DefaultVariantDeletionException

**Create**: `modules/epistola-core/.../templates/commands/variants/DefaultVariantDeletionException.kt`

```kotlin
class DefaultVariantDeletionException(val variantId: VariantId) : RuntimeException(
    "Cannot delete variant '$variantId' because it is the default. Reassign default first.",
)
```

---

## Step 5: VariantResolver Update

**Modify**: `modules/epistola-core/.../templates/services/VariantResolver.kt`

- Change `variants.find { it.attributes.isEmpty() }` → `variants.find { it.isDefault }`
- Update KDoc to say "default variant (is_default = true)"

---

## Step 6: UI Changes

### Icon sprite — add `star` icon

**Modify**: `modules/design-system/icons/generate-sprite.js`

- Add `"star"` to the `ICONS` array
- Regenerate: `pnpm --filter @epistola/design-system generate:icons`

### Template detail page

**Modify**: `apps/epistola/src/main/resources/templates/templates/detail.html`

Attributes column — replace empty-attributes "(default)" with `isDefault` badge:

```html
<td>
  <span th:if="${variant.isDefault}" class="badge badge-default">Default</span>
  <div class="tags" th:if="${!variant.attributes.isEmpty()}">
    <span
      th:each="entry : ${variant.attributes}"
      class="badge badge-outline"
      th:text="${entry.key + '=' + entry.value}"
    ></span>
  </div>
  <span th:if="${variant.attributes.isEmpty() and !variant.isDefault}" class="text-muted">-</span>
</td>
```

Actions column — add "Set as Default" button for non-default variants:

```html
<form
  th:unless="${variant.isDefault}"
  th:hx-post="@{/tenants/{tenantId}/templates/{id}/variants/{variantId}/set-default(...)}"
  hx-target="#variants-section"
  hx-swap="outerHTML"
  hx-confirm="Make this the default variant?"
>
  <button type="submit" class="btn btn-sm btn-icon btn-ghost" title="Set as default">
    <th:block th:replace="~{fragments/icon :: icon(name='star', class='ep-icon ep-icon-sm')}" />
  </button>
</form>
```

Disable delete button for default variant (replace form with disabled button + tooltip).

### Route handler

**Modify**: `apps/epistola/.../handlers/VariantRouteHandler.kt`

- Add `setDefaultVariant()` method — calls `SetDefaultVariant(...).execute()`, re-renders variants section
- Wrap `deleteVariant()` in try-catch for `DefaultVariantDeletionException` — re-render with error message

### Route registration

**Modify**: `apps/epistola/.../handlers/DocumentTemplateRoutes.kt`

- Add: `POST("/{id}/variants/{variantId}/set-default", variantHandler::setDefaultVariant)`

---

## Step 7: REST API + Contract

### Contract (`../epistola-contract/spec/components/schemas/variants.yaml`)

- Add `isDefault: boolean` (required) to `VariantDto` and `VariantSummaryDto`
- No change to `CreateVariantRequest` or `UpdateVariantRequest` (default managed separately)

### Contract — new endpoint path

- Add `POST /tenants/{tenantId}/templates/{templateId}/variants/{variantId}/set-default`

### Regenerate + publish

```bash
cd ../epistola-contract && npx @redocly/cli bundle epistola-api.yaml -o openapi.yaml
cd server-kotlin-springboot4 && ./gradlew publishToMavenLocal
```

### DTO mappers

**Modify**: `modules/rest-api/.../api/v1/shared/DtoMappers.kt`

- Add `isDefault = isDefault` to `VariantSummary.toDto()` and `TemplateVariant.toDto()`

### REST controller

**Modify**: `modules/rest-api/.../api/v1/EpistolaTemplateApi.kt`

- Add `setDefaultVariant()` endpoint

### Exception handler

**Modify**: `modules/rest-api/.../api/v1/ApiExceptionHandler.kt`

- Add handler for `DefaultVariantDeletionException` → 409 CONFLICT

---

## Step 8: Tests

### Update existing tests

**Modify**: `modules/epistola-core/.../templates/services/VariantResolverTest.kt`

- Add test: default variant with attributes still acts as fallback

### New tests

**Create**: `modules/epistola-core/.../templates/commands/variants/DefaultVariantTest.kt`

| Test                                                | Description                                               |
| --------------------------------------------------- | --------------------------------------------------------- |
| `first variant is automatically the default`        | CreateDocumentTemplate auto-creates default variant       |
| `subsequent variants are not default`               | CreateVariant sets is_default = false when variants exist |
| `set-default changes which variant is default`      | SetDefaultVariant flips the flag                          |
| `old default becomes non-default after set-default` | Verify mutual exclusion                                   |
| `blocks deletion of default variant`                | DeleteVariant throws DefaultVariantDeletionException      |
| `allows deletion of non-default variant`            | DeleteVariant succeeds for non-default                    |
| `allows deletion after reassigning default`         | SetDefault + Delete workflow                              |

---

## Step 9: Documentation + Cleanup

- **Modify**: `docs/attributes.md` — update default variant section (no longer tied to empty attributes)
- **Modify**: `CHANGELOG.md` — add entry for explicit default variant flag

---

## Verification

1. `./gradlew ktlintFormat && ./gradlew ktlintCheck`
2. `./gradlew test` — all existing + new tests pass
3. Manual: `./gradlew :apps:epistola:bootRun`
   - Create template → first variant shows "Default" badge
   - Create second variant → not default, shows "Set as default" star button
   - Click "Set as default" on second variant → it becomes default, first loses badge
   - Try to delete default variant → blocked
   - Delete non-default variant → works
4. Contract: `cd ../epistola-contract && ./gradlew build`
5. Full build: `pnpm install && pnpm build && ./gradlew build`
