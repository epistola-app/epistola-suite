# Theme System Implementation Plan

This document outlines the phased implementation of a theme/style manager system for Epistola Suite, enabling reusable styling across multiple templates.

## Overview

**Goal**: Allow users to define reusable style collections (themes) that can be shared across multiple templates, ensuring consistent branding and reducing duplication.

**Key Concepts**:
- **Theme**: A tenant-scoped collection of document styles and named block style presets
- **Block Style Preset**: A named set of style properties (like CSS classes) that blocks can reference
- **Style Cascade**: Theme → Document styles → Block preset → Block inline styles → TipTap marks

---

## Phase 1: Database & Domain Foundation

**Goal**: Create the database schema and core domain model for themes.

### Tasks

1. **Add ThemeId to EntityId.kt**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/common/ids/EntityId.kt`
   - Add `ThemeId` value class following existing pattern

2. **Add ThemeId column mapper**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/common/ids/JdbiIdSupport.kt`
   - Register column mapper for `ThemeId`

3. **Create database migration**
   - File: `apps/epistola/src/main/resources/db/migration/V7__create_themes.sql`
   - Create `themes` table with columns:
     - `id UUID PRIMARY KEY`
     - `tenant_id UUID NOT NULL REFERENCES tenants(id)`
     - `name VARCHAR(255) NOT NULL`
     - `description TEXT`
     - `document_styles JSONB NOT NULL DEFAULT '{}'`
     - `page_settings JSONB`
     - `block_style_presets JSONB`
     - `created_at`, `last_modified` timestamps
     - Unique constraint on `(tenant_id, name)`

4. **Create Theme domain entity**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/Theme.kt`
   - Define `Theme` data class with all properties

5. **Configure JDBI mapping**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/config/JdbiConfig.kt`
   - Register row mapper for `Theme`

### Verification
- Run `./gradlew test` - existing tests should pass
- Run migration against local database

---

## Phase 2: Theme CQRS Layer

**Goal**: Implement commands and queries for theme CRUD operations.

### Tasks

1. **CreateTheme command**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/commands/CreateTheme.kt`
   - Validate: name required, max 255 chars
   - Insert into database, return created Theme

2. **UpdateTheme command**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/commands/UpdateTheme.kt`
   - Partial updates (only provided fields)
   - Update `last_modified` timestamp

3. **DeleteTheme command**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/commands/DeleteTheme.kt`
   - Simple delete by ID
   - Templates referencing this theme will gracefully fall back

4. **GetTheme query**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/queries/GetTheme.kt`
   - Get theme by ID and tenant ID

5. **ListThemes query**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/queries/ListThemes.kt`
   - List all themes for a tenant

### Verification
- Write integration tests for each command/query
- Test duplicate name constraint
- Test cascade delete when tenant is deleted

---

## Phase 3: Theme REST API

**Goal**: Expose theme CRUD operations via REST endpoints.

### Tasks

1. **Create Theme DTOs**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/shared/ThemeDtoMappers.kt`
   - `ThemeDto`, `CreateThemeRequest`, `UpdateThemeRequest`
   - Mapper functions between domain and DTOs

2. **Create Theme API controller**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/EpistolaThemeApi.kt`
   - Endpoints:
     - `GET /v1/tenants/{tenantId}/themes` - List themes
     - `POST /v1/tenants/{tenantId}/themes` - Create theme
     - `GET /v1/tenants/{tenantId}/themes/{themeId}` - Get theme
     - `PATCH /v1/tenants/{tenantId}/themes/{themeId}` - Update theme
     - `DELETE /v1/tenants/{tenantId}/themes/{themeId}` - Delete theme

3. **Update OpenAPI spec** (if maintaining one)

### Verification
- Test all endpoints via HTTP client (curl, Postman, or tests)
- Verify JSON serialization of document_styles and block_style_presets
- Test error responses (404, 409 conflict)

---

## Phase 4: TemplateModel Theme Integration

**Goal**: Allow templates to reference themes and blocks to reference presets.

### Tasks

1. **Add themeId to TemplateModel**
   - File: `modules/template-model/src/main/kotlin/app/epistola/template/model/TemplateModel.kt`
   - Add `val themeId: String? = null` field
   - All variants of a version share this theme

2. **Add stylePreset to Block classes**
   - File: `modules/template-model/src/main/kotlin/app/epistola/template/model/TemplateModel.kt`
   - Add `abstract val stylePreset: String?` to sealed class `Block`
   - Add `val stylePreset: String? = null` to all block implementations

3. **Update JSON serialization tests**
   - Ensure backward compatibility (missing fields default to null)

### Verification
- Verify templates without themeId still work (null handling)
- Test JSON round-trip with new fields
- Existing templates should be unaffected

---

## Phase 5: Style Resolution Service

**Goal**: Implement the logic that merges theme styles with template/block styles.

### Tasks

1. **Create ThemeStyleResolver service**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/themes/ThemeStyleResolver.kt`
   - Methods:
     - `resolveDocumentStyles(tenantId, templateModel)` - merges theme + template document styles
     - `resolveBlockStyles(theme, block)` - resolves preset + inline styles
     - `getTheme(tenantId, themeId)` - fetch theme (cached if needed)
   - Style merge: overlay wins when not null, base used as fallback

2. **Update GenerationService**
   - File: `apps/epistola/src/main/kotlin/app/epistola/suite/generation/GenerationService.kt`
   - Inject `ThemeStyleResolver`
   - Before rendering, resolve document styles from theme

3. **Update DirectPdfRenderer/StyleApplicator**
   - File: `modules/generation/src/main/kotlin/app/epistola/generation/pdf/StyleApplicator.kt`
   - Pass theme to render context
   - When applying block styles, resolve preset first

4. **Handle missing presets**
   - If block references preset that doesn't exist: silently ignore
   - Use document styles / inline styles as fallback

### Verification
- Unit tests for style merging logic
- Integration test: template with theme renders with theme styles
- Integration test: template without theme renders with own styles
- Integration test: missing preset falls back silently

---

## Phase 6: Theme Editor UI

**Goal**: Build the UI for creating and managing themes.

### Tasks

1. **Theme list page**
   - Route: `/tenants/{tenantId}/themes`
   - List all themes with name, description
   - Create new theme button
   - Delete theme (with confirmation)

2. **Theme editor page**
   - Route: `/tenants/{tenantId}/themes/{themeId}`
   - Edit name, description
   - **Document Styles section**:
     - Font family dropdown
     - Font size input
     - Color picker
     - Background color picker
     - Text alignment selector
     - Line height, letter spacing inputs
   - **Page Settings section** (optional):
     - Page format dropdown (A4, Letter, Custom)
     - Orientation selector
     - Margin inputs

3. **Block Style Presets section**
   - Add/remove presets
   - For each preset:
     - Name input
     - Style property editors (same as document styles + margins, padding)
   - Live preview of preset styling

4. **Create theme modal/page**
   - Name, description inputs
   - Initial document styles

### UI Components Needed
- Color picker component
- Font family selector
- Size input (with unit: px, pt, em)
- Style property form (reusable for document styles and presets)
- Preset list with add/remove

### Verification
- Create a theme with document styles
- Create presets in the theme
- Verify theme is saved correctly via API

---

## Phase 7: Template Editor Theme Integration

**Goal**: Allow users to assign themes to templates and apply presets to blocks.

### Tasks

1. **Theme selector in template settings**
   - When editing template/version, add "Theme" dropdown
   - Shows themes from tenant
   - Saves themeId to TemplateModel
   - "No theme" option

2. **Preset selector on blocks**
   - When block is selected, show "Style Preset" dropdown
   - Populated from template's theme's presets
   - "No preset" option
   - Shows available presets: heading1, heading2, etc.

3. **Style inheritance preview**
   - In editor, show styles from theme applied to blocks
   - Visual indication when preset is applied

4. **Override indicator**
   - Show when block has inline styles overriding preset

### Verification
- Assign theme to template
- Apply preset to block
- Verify rendered PDF uses theme + preset styles
- Verify overrides work correctly

---

## Phase 8: Testing & Polish

**Goal**: Comprehensive testing and edge case handling.

### Tasks

1. **Integration tests**
   - Full flow: create theme → create template with theme → generate document
   - Theme deletion doesn't break templates
   - Preset changes reflect in next render

2. **Edge cases**
   - Template with theme that was deleted
   - Block with preset that doesn't exist in theme
   - Theme with empty presets
   - Concurrent theme updates

3. **Performance considerations**
   - Theme lookup caching during batch generation
   - Consider theme data size limits

4. **Documentation**
   - Update CHANGELOG.md
   - User guide for themes feature

### Verification
- All tests pass
- Manual testing of full workflow
- Performance acceptable for batch operations

---

## Data Model Summary

### Theme (new table)
```sql
themes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    document_styles JSONB,      -- DocumentStyles object
    page_settings JSONB,        -- PageSettings object
    block_style_presets JSONB,  -- Map<String, Map<String, Any>>
    created_at TIMESTAMPTZ,
    last_modified TIMESTAMPTZ
)
```

### TemplateModel (modified)
```kotlin
data class TemplateModel(
    val id: String,
    val name: String,
    val version: Int = 1,
    val themeId: String? = null,           // NEW
    val pageSettings: PageSettings,
    val blocks: List<Block> = emptyList(),
    val documentStyles: DocumentStyles = DocumentStyles(),
)
```

### Block (modified)
```kotlin
sealed class Block {
    abstract val id: String
    abstract val type: String
    abstract val styles: Map<String, Any>?
    abstract val stylePreset: String?      // NEW
}
```

---

## Style Cascade

Order of precedence (lowest to highest):

1. **Theme document styles** - Base defaults from theme
2. **Template document styles** - Override theme defaults
3. **Theme block preset** - When block has stylePreset
4. **Block inline styles** - Override preset
5. **TipTap marks** - Inline text formatting

---

## API Endpoints Summary

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/tenants/{tenantId}/themes` | List all themes |
| POST | `/v1/tenants/{tenantId}/themes` | Create theme |
| GET | `/v1/tenants/{tenantId}/themes/{themeId}` | Get theme |
| PATCH | `/v1/tenants/{tenantId}/themes/{themeId}` | Update theme |
| DELETE | `/v1/tenants/{tenantId}/themes/{themeId}` | Delete theme |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Soft reference (themeId in JSON) | Templates remain valid if theme is deleted |
| Theme at tenant level | Themes shared within tenant, not globally |
| One theme per template version | All variants share; different versions can differ |
| Presets like CSS classes | Familiar mental model, explicit application |
| Silent preset fallback | Missing preset → use defaults, no errors |
| Snippets separate feature | Content reuse is different from style reuse |
| No theme versioning | Create new themes instead of versioning |
| No theme inheritance | Keep initial implementation simple |
