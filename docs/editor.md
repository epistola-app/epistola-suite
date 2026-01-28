# Frontend Interactive Components Architecture

This document describes the architecture for interactive JavaScript components in Epistola Suite, including the template editor, schema editor, and future interactive modules.

## Overview

Epistola uses a **hybrid rendering approach**:
- **Server-side**: Thymeleaf + HTMX for most UI (navigation, forms, lists)
- **Client-side**: JavaScript modules for rich interactive components that require complex state management

Interactive components are built as **embeddable modules** that expose mount functions. The server renders a container element and passes initial data via JavaScript variables, then the module takes over.

## Build System

### Dependency Strategy

Interactive modules share common dependencies (React, Zustand, etc.) to avoid duplication. This is achieved via **Import Maps** with self-hosted ESM dependencies.

**How it works:**
1. Shared dependencies are copied as ESM bundles to `/vendor/`
2. An import map in the HTML maps bare specifiers to these files
3. Modules are built with dependencies marked as **externals**
4. Browser resolves imports at runtime via the import map

**Example import map:**
```html
<script type="importmap">
{
  "imports": {
    "react": "/vendor/react.js",
    "react-dom": "/vendor/react-dom.js",
    "react-dom/client": "/vendor/react-dom-client.js",
    "react/jsx-runtime": "/vendor/react-jsx-runtime.js",
    "zustand": "/vendor/zustand.js",
    "zustand/middleware/immer": "/vendor/zustand-middleware-immer.js",
    "immer": "/vendor/immer.js"
  }
}
</script>
```

**Benefits:**
- Dependencies loaded once, shared across all modules
- Smaller module bundles (template-editor.js is ~750KB vs ~1.1MB when React was bundled)
- Native browser feature (Chrome 89+, Firefox 108+, Safari 16.4+)
- Works offline (self-hosted)

### Vendor Dependencies

The `modules/vendor/` package manages shared dependencies:

```
modules/vendor/
├── package.json          # Lists all shared dependencies
├── build.mjs             # esbuild script to bundle dependencies
├── build.gradle.kts      # Copies ESM bundles to resources
└── dist/
    ├── react.js
    ├── react-dom.js
    ├── react-dom-client.js
    ├── react-jsx-runtime.js
    ├── zustand.js
    ├── zustand-middleware-immer.js
    └── immer.js
```

The build process uses esbuild to create single-file ESM bundles for each dependency. Dependencies are served from `/vendor/` in the application.

### pnpm Workspace

All frontend modules are managed as a pnpm workspace at the repository root:

```
epistola-suite/
├── pnpm-workspace.yaml
├── package.json                    # Root scripts
├── modules/
│   ├── vendor/                     # Shared dependencies (React, Zustand, etc.)
│   └── editor/                     # Template editor (includes Data Contract Manager)
```

**Root `pnpm-workspace.yaml`:**
```yaml
packages:
  - 'modules/*'
```

**Root scripts:**
```bash
pnpm build              # Build all modules in dependency order
pnpm dev                # Run all dev servers in parallel
pnpm --filter @epistola/editor build   # Build specific module
```

### Module Structure

Each module follows this structure:

```
modules/<name>/
├── package.json            # Dependencies, scripts
├── tsconfig.json           # Extends shared base config
├── vite.config.ts          # Uses shared vite base config
├── build.gradle.kts        # Gradle integration
├── src/
│   ├── lib.tsx             # Public mount API
│   ├── main.tsx            # Standalone dev entry
│   ├── components/         # React components
│   ├── store/              # Zustand state management
│   └── index.css           # Tailwind + custom styles
└── dist/
    ├── <name>.js           # ES module bundle
    └── <name>.css          # Compiled styles
```

### Gradle Integration

Each module has a `build.gradle.kts` that integrates with pnpm:

```kotlin
val pnpmInstall by tasks.registering(PnpmTask::class) {
    workingDir.set(file("${rootProject.projectDir}"))
    args.set(listOf("install", "--frozen-lockfile"))
}

val pnpmBuild by tasks.registering(PnpmTask::class) {
    dependsOn(pnpmInstall)
    workingDir.set(file("${rootProject.projectDir}"))
    args.set(listOf("--filter", "@epistola/<module-name>", "build"))
    outputs.dir("dist")
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(pnpmBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/<module-name>"))
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}
```

The built assets are served from `/META-INF/resources/<module-name>/` which Spring Boot serves as static resources.

## Build Configuration

Each module (like `modules/editor/`) contains its own TypeScript and Vite configuration.

### Externals Pattern

Modules exclude shared dependencies from their bundles. These are resolved at runtime via import maps:

```typescript
// vite.config.ts - shared dependencies excluded from bundle
rollupOptions: {
  external: [
    'react',
    'react-dom',
    'react-dom/client',
    'react/jsx-runtime',
    'zustand',
    'zustand/middleware/immer',
    'immer',
  ],
}
```

When built, modules preserve import statements like `import { useState } from 'react'`. The browser's import map resolves these to vendor-bundled files.

### Type Definitions

Types are defined within the editor module at `modules/editor/src/main/typescript/types/`:

```typescript
// types/template.ts
export interface TemplateModel {
  pageSettings: PageSettings;
  blocks: Block[];
  documentStyles?: DocumentStyles;
}

// types/schema.ts - Zod-based schema types
export const FieldTypeSchema = z.enum([
  'string', 'number', 'integer', 'boolean', 'array', 'object'
]);
```

## Template Editor

The template editor (`modules/editor/`) provides a visual editor for designing document templates with dynamic data binding.

### Technology Stack

- **React 19** - UI framework
- **TypeScript** - Type safety
- **Vite** - Build tool (library mode)
- **Zustand + Immer** - State management
- **TipTap 3** - Rich text editing
- **dnd-kit** - Drag and drop
- **Tailwind CSS 4** - Styling

### Mount API

```typescript
import { mountEditor } from '/editor/template-editor.js';

const instance = mountEditor({
  container: document.getElementById('editor-container'),
  template: templateData,
  dataStructure: dataStructureData,  // For expression autocomplete
  onSave: async (template) => {
    await fetch(`/api/templates/${id}`, {
      method: 'PATCH',
      body: JSON.stringify({ templateModel: template }),
    });
  },
  onCancel: () => window.history.back(),
});

// Instance methods
instance.getTemplate();
instance.setTemplate(newTemplate);
instance.setDataStructure(newDataStructure);
instance.unmount();
```

### Block Types

The template editor supports these block types:

| Type | Description |
|------|-------------|
| `TextBlock` | Rich text content with expression chips (`{{customer.name}}`) |
| `ContainerBlock` | Generic nestable container |
| `ConditionalBlock` | If/else based on expression (`{{hasDiscount}}`) |
| `LoopBlock` | Array iteration with item/index aliases |
| `ColumnsBlock` | Multi-column layout with flexible widths |
| `TableBlock` | Structured tables with headers and data rows |

### Thymeleaf Integration

Pages using interactive modules include the import map via a shared fragment, then load the module:

**Layout fragment** (`fragments/importmap.html`):
```html
<script type="importmap" th:fragment="importmap">
{
  "imports": {
    "react": "/vendor/react.js",
    "react-dom": "/vendor/react-dom.js",
    "react-dom/client": "/vendor/react-dom-client.js",
    "react/jsx-runtime": "/vendor/react-jsx-runtime.js",
    "zustand": "/vendor/zustand.js",
    "zustand/middleware/immer": "/vendor/zustand-middleware-immer.js",
    "immer": "/vendor/immer.js"
  }
}
</script>
```

**Editor page** (`templates/editor.html`):
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <th:block th:replace="~{fragments/importmap :: importmap}" />
    <link rel="stylesheet" th:href="@{/editor/template-editor.css}">
</head>
<body>
    <div id="editor-container"></div>

    <script th:inline="javascript">
        window.TEMPLATE_MODEL = /*[[${templateModel}]]*/ {};
        window.DATA_STRUCTURE = /*[[${dataStructure}]]*/ null;
    </script>
    <script type="module">
        import { mountEditor } from '/editor/template-editor.js';

        mountEditor({
            container: document.getElementById('editor-container'),
            template: window.TEMPLATE_MODEL,
            dataStructure: window.DATA_STRUCTURE,
            onSave: async (template) => { /* save via REST */ }
        });
    </script>
</body>
</html>
```

The import map must appear **before** any `<script type="module">` tags.

## Data Contract Manager

The Data Contract Manager is a dialog component embedded within the template editor (`modules/editor/`) that provides schema definition and test data management.

### Purpose

- Define the data contract (JSON Schema) that templates consume
- Create and validate test data examples against the schema
- Detect schema compatibility issues and suggest migrations
- Serve as the "contract" between data and templates

### Architecture

The Data Contract Manager is opened via the editor header's "Data" button. It consists of:

**Main Components:**
- `DataContractManager.tsx` - Main dialog with tab-based UI
- `DataExamplesManager.tsx` - Example selection dropdown and settings
- `MigrationAssistant.tsx` - Schema migration suggestions dialog
- `ValidationMessages.tsx` - Error and warning display
- `DialogFooterActions.tsx` - Save/Close with unsaved changes indicator

**State Management:**
- `useDataContractDraft.ts` hook - Local-first draft state with dirty tracking

**Key Features:**
- Visual schema editor with nested field support
- "Generate from example" to infer schema from test data
- Advanced JSON toggle for direct JSON Schema editing
- Expression path extraction from template blocks
- Impact analysis for expressions not covered by schema
- Schema compatibility validation with auto-fix suggestions

### JSON Schema Support

The schema editor supports a **core subset** of JSON Schema 2020-12:

**Supported types:**
- `string` - Text values
- `number` - Floating-point numbers
- `integer` - Whole numbers
- `boolean` - True/false
- `array` - Lists of items
- `object` - Nested structures

**Supported constraints:**

| Type | Constraints |
|------|-------------|
| String | `minLength`, `maxLength`, `pattern`, `format` |
| Number | `minimum`, `maximum` |
| Array | `items` (single type) |
| Object | `properties`, `required` |

### UX Patterns

**Local-First Draft State:**
- All edits modify local draft state
- Backend persistence only on explicit Save
- Unsaved changes indicator in dialog footer
- Close confirmation when dirty

**Save & Stay Open:**
- Save button persists changes and shows success feedback
- Dialog remains open for iterative editing
- Close button exits (with confirmation if dirty)

**Validation Display:**
- Red errors block saving (JSON syntax, structure)
- Yellow warnings allow saving (schema validation)
- Parallel display of both error types

**Migration Assistant:**
- Detects when schema changes break existing examples
- Offers auto-fix for simple type conversions (string↔number, string↔boolean)
- Allows force update to save despite warnings

### Example Usage

Given this schema defined in Data Contract Manager:
```json
{
  "type": "object",
  "properties": {
    "customer": {
      "type": "object",
      "properties": {
        "name": { "type": "string" },
        "email": { "type": "string" }
      },
      "required": ["name"]
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "description": { "type": "string" },
          "price": { "type": "number" }
        }
      }
    }
  }
}
```

Templates can use expressions like:
- `{{customer.name}}`
- `{{customer.email}}`
- `{{items[0].description}}`
- Loop over `{{items}}` with item alias

## Domain Model

### Template with Embedded Data Contract

Templates contain their data contract (schema and examples) directly:

```kotlin
data class DocumentTemplate(
    val id: Long,
    val tenantId: Long,
    val name: String,
    val dataModel: ObjectNode?,        // JSON Schema defining data structure
    val dataExamples: List<DataExample>, // Test data examples
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

data class DataExample(
    val name: String,
    val data: ObjectNode,
)
```

Template content (visual layout) is stored in versions:

```kotlin
data class TemplateVersion(
    val id: Long,
    val variantId: Long,
    val versionNumber: Int?,           // null for drafts
    val status: VersionStatus,         // DRAFT, PUBLISHED, ARCHIVED
    val templateModel: TemplateModel,  // Visual layout definition
    val createdAt: OffsetDateTime,
)
```

### Variants and Versions

Templates support variants (different presentations) and versions (immutable snapshots):

```
DocumentTemplate: "Invoice"
├── dataModel: { customer: {...}, items: [...] }
├── dataExamples: [{ name: "Sample", data: {...} }]
└── Variants:
    ├── "Default" (lang=en)
    │   ├── Draft (version=null)
    │   ├── v1 (published)
    │   └── v2 (published)
    └── "Dutch" (lang=nl)
        ├── Draft (version=null)
        └── v1 (published)
```

Environment activations determine which version is served in each environment (staging, production, etc.).

## Development Workflow

### Running Development Servers

```bash
# All modules
pnpm dev

# Specific module
pnpm --filter @epistola/editor dev
```

### Building

```bash
# All modules (via Gradle - includes resource copying)
gradle build

# Just frontend (via pnpm)
pnpm build

# Specific module
pnpm --filter @epistola/editor build
```

### Adding a New Module

1. Create directory: `modules/<name>/`
2. Create `package.json` with appropriate dependencies
3. Create `tsconfig.json` with TypeScript configuration
4. Create `vite.config.ts` with library mode configuration
5. Add externals for shared dependencies (react, zustand, etc.)
6. Implement mount function in `src/lib.tsx`
7. Ensure vendor dependencies cover any new shared packages