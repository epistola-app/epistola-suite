# Variant Attributes

Variant attributes are structured key-value pairs assigned to template variants. They serve two purposes: describing what a variant represents (e.g. "this is the Dutch version") and enabling automatic variant selection during document generation based on matching criteria.

## Concepts

### Attribute Definitions

Before attributes can be used on variants, they must be defined in a **tenant-scoped registry**. Each attribute definition specifies:

| Field | Description |
|-------|-------------|
| **id** | Slug identifier (3-50 chars), e.g. `language`, `brand` |
| **displayName** | Human-readable label, e.g. "Language", "Brand" |
| **allowedValues** | Optional list of permitted values. If empty, any value is accepted. |

Attribute definitions are managed per tenant. Variants can only use attributes that exist in their tenant's registry.

### Variant Attributes

Each variant has an `attributes` map (`Map<String, String>`) stored as JSONB. When creating or updating a variant, its attributes are validated against the registry:

1. Every attribute key must correspond to an existing attribute definition for the tenant.
2. If the definition has `allowedValues`, the value must be in that list.

A variant with **empty attributes** (`{}`) is considered the **default variant**. Only one default variant is allowed per template.

**Example:** An invoice template might have three variants:

| Variant | Attributes |
|---------|-----------|
| `dutch` | `{ "language": "nl" }` |
| `english` | `{ "language": "en" }` |
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
       score = (number of optional attribute matches * 10) + total variant attributes
4. SELECT the variant with the highest score
5. If tied → AmbiguousVariantResolutionException
6. If no candidates after step 2 → fall back to the default variant (empty attributes)
7. If no default variant exists → NoMatchingVariantException
```

The scoring formula favours variants that match more optional criteria (`* 10` weight) while using the total number of variant attributes as a tiebreaker to prefer more specific variants.

### Resolution Examples

Given these variants on an `invoice` template:

| Variant | Attributes |
|---------|-----------|
| `default` | `{}` |
| `dutch` | `{ "language": "nl" }` |
| `english` | `{ "language": "en" }` |
| `english-corporate` | `{ "language": "en", "brand": "corporate" }` |

**Example 1: Required match**
```
Criteria: language=en (required)
→ Candidates: english (score=1), english-corporate (score=2)
→ Result: english-corporate (higher score due to more attributes)
```

**Example 2: Required + optional**
```
Criteria: language=en (required), brand=corporate (optional)
→ Candidates: english (score=0+1=1), english-corporate (score=10+2=12)
→ Result: english-corporate
```

**Example 3: No match, falls back to default**
```
Criteria: language=fr (required)
→ Candidates: none match
→ Result: default variant (empty attributes)
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

| Column | Type | Description |
|--------|------|-------------|
| `id` | `VARCHAR(50)` | Slug identifier (PK with tenant_id) |
| `tenant_id` | `VARCHAR(63)` | FK to `tenants` |
| `display_name` | `VARCHAR(100)` | Human-readable label |
| `allowed_values` | `JSONB` | Array of permitted values (empty = any) |
| `created_at` | `TIMESTAMPTZ` | Creation timestamp |
| `last_modified` | `TIMESTAMPTZ` | Last modification timestamp |

### `template_variants.attributes`

The `attributes` column (JSONB, formerly `tags`) stores the variant's attribute map as `{"key": "value", ...}`.

## Key Files

| File | Purpose |
|------|---------|
| `modules/epistola-core/.../attributes/model/VariantAttributeDefinition.kt` | Domain model |
| `modules/epistola-core/.../attributes/commands/CreateAttributeDefinition.kt` | Create command |
| `modules/epistola-core/.../attributes/commands/UpdateAttributeDefinition.kt` | Update command |
| `modules/epistola-core/.../attributes/commands/DeleteAttributeDefinition.kt` | Delete command |
| `modules/epistola-core/.../attributes/queries/ListAttributeDefinitions.kt` | List query |
| `modules/epistola-core/.../attributes/queries/GetAttributeDefinition.kt` | Get query |
| `modules/epistola-core/.../templates/services/VariantResolver.kt` | Resolution algorithm |
| `modules/epistola-core/.../templates/commands/variants/AttributeValidation.kt` | Validation logic |
| `modules/epistola-core/.../templates/services/VariantResolverTest.kt` | Resolution tests |
