# Variant Attributes

Variant attributes are structured key-value pairs assigned to template variants. They serve two purposes: describing what a variant represents (e.g. "this is the Dutch version") and enabling automatic variant selection during document generation based on matching criteria.

## Concepts

### Attribute Definitions

Before attributes can be used on variants, they must be defined in a **tenant-scoped registry**. Each attribute definition specifies:

| Field                                 | Description                                                                                                                                                                     |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **id**                                | Slug identifier (3-50 chars), e.g. `language`, `brand`                                                                                                                          |
| **displayName**                       | Human-readable label, e.g. "Language", "Brand"                                                                                                                                  |
| **allowedValues**                     | Optional list of permitted values. If empty and no code list is bound, any value is accepted.                                                                                   |
| **codeListCatalogKey / codeListSlug** | Optional binding to a [code list](code-lists.md). Mutually exclusive with `allowedValues` — an attribute either uses inline values, binds to a code list, or accepts any value. |

Attribute definitions are managed per tenant. Variants can only use attributes that exist in their tenant's registry.

A definition belongs to a catalog. A variant names it either **qualified**, `"<catalog>.<slug>"` (for example `system.locale`), which picks exactly that definition, or by its **bare** slug, which is resolved across all of the tenant's catalogs. Prefer the qualified form whenever the same slug exists in more than one catalog.

A definition cannot be deleted, and values cannot be removed from its `allowedValues`, while a variant still uses it. The check counts variants in **every** catalog of the tenant and both key forms; a bare key is counted for every catalog that defines that slug, so with an ambiguous slug the check errs on the side of refusing. Dropping a definition in a catalog upgrade reports the same variants as conflicts.

For longer or shared value sets (locales, country codes, custom taxonomies),
prefer a **code list** binding over inline `allowedValues`. See
[`code-lists.md`](code-lists.md) for the full design.

### Variant Attributes

Each variant has an `attributes` map (`Map<String, String>`) stored as JSONB. When creating or updating a variant, its attributes are validated against the registry:

1. Every attribute key must correspond to an existing attribute definition for the tenant.
2. If the definition has `allowedValues`, the value must be in that list.

Each template has exactly one **default variant** (marked with `is_default = true`). The default variant acts as the fallback when no variant matches the selection criteria. The first variant created for a template is automatically the default. The default variant can have any attributes — it is not required to have empty attributes.

**Example:** An invoice template might have three variants:

| Variant             | Attributes                                   |
| ------------------- | -------------------------------------------- |
| `dutch`             | `{ "language": "nl" }`                       |
| `english`           | `{ "language": "en" }`                       |
| `english-corporate` | `{ "language": "en", "brand": "corporate" }` |

## Variant Resolution

When generating a document, callers can either specify a `variantId` explicitly or provide **attribute criteria** to let the system select the best matching variant automatically. These two approaches are mutually exclusive.

### Selection Criteria

Attribute criteria are provided as a list of key-value pairs, each marked as **required** or **optional**:

```json
{
  "attributes": [
    { "key": "language", "value": "en", "required": true },
    { "key": "brand", "value": "corporate", "required": false }
  ]
}
```

- **Required** (default): The variant **must** have this exact attribute value. Variants that don't match are excluded.
- **Optional** (`required: false`): Preferred but not mandatory. Used for scoring when multiple variants match the required criteria.

### Resolution Algorithm

```
Input: templateId + list of { key, value, required }

1. Fetch all variants for the template
2. FILTER: Keep only variants matching ALL required attributes
3. SCORE remaining candidates:
       score = (required attribute matches * 100) + (optional attribute matches * 10)
4. SELECT the variant with the highest score
5. If tied → AmbiguousVariantResolutionException
6. If no candidates after step 2 → fall back to the default variant (is_default = true)
7. If no default variant exists → NoMatchingVariantException
```

Only attributes named in the request count. A variant's other attributes neither help nor hurt it, so
there is no "most specific variant" tiebreak: when two candidates match the request equally, the
request is ambiguous and fails with `409 AMBIGUOUS_VARIANT`. Add an optional criterion to tell them
apart, or ask for the variant by id.

Two variants of one template may not have the same attribute set — creating or updating one is
refused, naming the variant that already has it, because no request could ever tell the two apart.
Key order does not matter. Variants without attributes are exempt: they never match a required
criterion and are only reachable by id.

### Resolution Examples

Given these variants on an `invoice` template:

| Variant             | Attributes                                   | Default |
| ------------------- | -------------------------------------------- | ------- |
| `default`           | `{}`                                         | Yes     |
| `dutch`             | `{ "language": "nl" }`                       | No      |
| `english`           | `{ "language": "en" }`                       | No      |
| `english-corporate` | `{ "language": "en", "brand": "corporate" }` | No      |

**Example 1: Required match**

```
Criteria: language=nl (required)
→ Candidates: dutch (score=100)
→ Result: dutch
```

**Example 2: Required + optional**

```
Criteria: language=en (required), brand=corporate (optional)
→ Candidates: english (score=100+0=100), english-corporate (score=100+10=110)
→ Result: english-corporate
```

**Example 2b: Required only, two candidates tie**

```
Criteria: language=en (required)
→ Candidates: english (score=100), english-corporate (score=100)
→ 409 AMBIGUOUS_VARIANT — english-corporate's extra attribute does not count
```

**Example 3: No match, falls back to default**

```
Criteria: language=fr (required)
→ Candidates: none match
→ Result: default variant (is_default = true)
```

**Example 4: No match, no default**

```
Criteria: language=fr (required), no default variant exists
→ NoMatchingVariantException
```

## REST API

### Generating with Attributes

Instead of providing `variantId`, supply `attributes` in the generation request:

```http
POST /api/v1/tenants/acme-corp/generation/generate
Content-Type: application/vnd.epistola.v1+json

{
  "templateId": "invoice",
  "attributes": [
    { "key": "language", "value": "en", "required": true },
    { "key": "brand", "value": "corporate", "required": false }
  ],
  "versionId": 1,
  "data": { ... }
}
```

The same `attributes` field is available on `BatchGenerationItem` for batch generation. Within a batch, each item can use either `variantId` or `attributes` independently.

### Attribute Definition Management

Attribute definitions are managed through the UI. The attribute registry is tenant-scoped and accessible from the navigation sidebar under **Attributes**.

## Database Schema

### `variant_attribute_definitions`

| Column           | Type           | Description                             |
| ---------------- | -------------- | --------------------------------------- |
| `id`             | `VARCHAR(50)`  | Slug identifier (PK with tenant_key)    |
| `tenant_key`     | `VARCHAR(63)`  | FK to `tenants`                         |
| `display_name`   | `VARCHAR(100)` | Human-readable label                    |
| `allowed_values` | `JSONB`        | Array of permitted values (empty = any) |
| `created_at`     | `TIMESTAMPTZ`  | Creation timestamp                      |
| `last_modified`  | `TIMESTAMPTZ`  | Last modification timestamp             |

### `template_variants.attributes`

The `attributes` column (JSONB) stores the variant's attribute map as `{"key": "value", ...}`.

### `template_variants.is_default`

Boolean flag indicating the default variant for a template. Enforced by a partial unique index `WHERE is_default = TRUE` to guarantee exactly one default per template. The first variant created for a template is automatically the default.

## Key Files

| File                                                                           | Purpose              |
| ------------------------------------------------------------------------------ | -------------------- |
| `modules/epistola-core/.../attributes/model/VariantAttributeDefinition.kt`     | Domain model         |
| `modules/epistola-core/.../attributes/commands/CreateAttributeDefinition.kt`   | Create command       |
| `modules/epistola-core/.../attributes/commands/UpdateAttributeDefinition.kt`   | Update command       |
| `modules/epistola-core/.../attributes/commands/DeleteAttributeDefinition.kt`   | Delete command       |
| `modules/epistola-core/.../attributes/queries/ListAttributeDefinitions.kt`     | List query           |
| `modules/epistola-core/.../attributes/queries/GetAttributeDefinition.kt`       | Get query            |
| `modules/epistola-core/.../templates/services/VariantResolver.kt`              | Resolution algorithm |
| `modules/epistola-core/.../templates/commands/variants/AttributeValidation.kt` | Validation logic     |
| `modules/epistola-core/.../templates/services/VariantResolverTest.kt`          | Resolution tests     |
