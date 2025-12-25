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
│   ├── shared/                     # Common types, base configs
│   ├── editor/                     # Template editor
│   └── schema-editor/              # Data structure editor
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

## Shared Module

The `modules/shared/` package contains:

### Base TypeScript Configuration

**`tsconfig.base.json`** - Extended by all modules:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "declaration": true,
    "declarationMap": true
  }
}
```

### Base Vite Configuration

**`vite.base.config.ts`** - Factory function for library mode with externals:
```typescript
// Shared dependencies - excluded from module bundles
const EXTERNALS = [
  'react',
  'react-dom',
  'react-dom/client',
  'react/jsx-runtime',
  'zustand',
  'immer',
];

export function createBaseConfig(options: {
  name: string;
  fileName: string;
  entry: string;
  port?: number;
}): UserConfig {
  return {
    plugins: [react(), tailwindcss()],
    build: {
      lib: {
        entry: options.entry,
        name: options.name,
        fileName: options.fileName,
        formats: ['es'],
      },
      rollupOptions: {
        external: EXTERNALS,
      },
    },
  };
}
```

When built, modules will have import statements like `import { useState } from 'react'` preserved in the output. The browser's import map resolves these to the actual files.

### Shared Types

Types used across modules are defined once:

```typescript
// types/data-structure.ts
export interface DataStructure {
  id: string;
  name: string;
  description?: string;
  version: number;
  schema: JsonSchema;
  examples: DataExample[];
}

export interface DataExample {
  name: string;
  description?: string;
  data: JsonObject;
}

// types/template.ts
export interface Template {
  id: string;
  name: string;
  version: number;
  dataStructureId?: string;
  pageSettings: PageSettings;
  blocks: Block[];
  documentStyles?: DocumentStyles;
}
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

## Schema Editor

The schema editor (`modules/schema-editor/`) provides a visual interface for defining data structures (JSON Schema) and managing test data examples.

### Purpose

- Define the structure of data that templates can consume
- Create and validate test data examples
- Serve as the "contract" between data and templates

### Technology Stack

Same as Template Editor (React 19, TypeScript, Vite, Zustand, dnd-kit, Tailwind).

### Mount API

```typescript
import { mountSchemaEditor } from '/schema-editor/schema-editor.js';

const instance = mountSchemaEditor({
  container: document.getElementById('schema-editor-container'),
  dataStructure: dataStructureData,
  onSave: async (dataStructure) => {
    await fetch(`/api/data-structures/${id}`, {
      method: 'PUT',
      body: JSON.stringify(dataStructure),
    });
  },
  readOnly: false,
});

// Instance methods
instance.getDataStructure();
instance.setDataStructure(newDataStructure);
instance.getSchema();
instance.getExamples();
instance.validate();  // Returns { valid, errors }
instance.unmount();
```

### JSON Schema Support

The schema editor supports a **core subset** of JSON Schema 2020-12:

**Supported types:**
- `string` - Text values
- `number` - Floating-point numbers
- `integer` - Whole numbers
- `boolean` - True/false
- `array` - Lists of items
- `object` - Nested structures
- `null` - Null values

**Supported constraints:**

| Type | Constraints |
|------|-------------|
| String | `minLength`, `maxLength`, `pattern`, `format` (email, date, uri, etc.) |
| Number | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` |
| Array | `items`, `minItems`, `maxItems`, `uniqueItems` |
| Object | `properties`, `required`, `additionalProperties` (boolean) |

**Not supported (initially):**
- `$ref` - Schema references
- `allOf`, `anyOf`, `oneOf`, `not` - Composition
- `if`/`then`/`else` - Conditional schemas
- `definitions` - Reusable schema fragments

### UI Components

```
SchemaEditorLayout
├── SchemaTree (left panel)
│   └── SchemaNode (recursive, expandable)
│       ├── TypeIcon (string, number, object, array, etc.)
│       ├── PropertyName (editable)
│       ├── RequiredBadge
│       └── AddChildButton
│
├── PropertyPanel (right panel)
│   ├── TypeSelector
│   ├── DescriptionInput
│   ├── Constraints (type-specific)
│   └── EnumEditor
│
└── ExamplesPanel (bottom/tab)
    ├── ExampleList
    ├── ExampleEditor (JSON)
    └── ValidationErrors
```

### Example Usage

Given this schema:
```json
{
  "type": "object",
  "properties": {
    "customer": {
      "type": "object",
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "email": { "type": "string", "format": "email" }
      },
      "required": ["name"]
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "description": { "type": "string" },
          "price": { "type": "number", "minimum": 0 }
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

### DataStructure Entity

DataStructures are standalone entities that templates reference:

```kotlin
data class DataStructure(
    val id: Long,
    val tenantId: Long,
    val name: String,
    val description: String?,
    val version: Int = 1,
    val schema: ObjectNode,           // JSON Schema
    val examples: List<DataExample>,  // Test data
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
```

### Template-DataStructure Relationship

Templates reference a DataStructure by ID:

```kotlin
data class DocumentTemplate(
    val id: Long,
    val tenantId: Long,
    val name: String,
    val dataStructureId: Long?,        // References DataStructure
    val variantInfo: VariantInfo?,     // For future variant support
    val templateModel: TemplateModel?,
    // ...
)
```

This enables:
- **Reusability**: Multiple templates can share one DataStructure
- **Variants**: Different visual designs for the same data (e.g., Dutch/English invoices)
- **Validation**: When schema changes, affected templates can be validated

### Future: Template Variants

Templates can be organized as variants of the same data structure:

```
DataStructure: "Invoice Data"
├── Template: "Invoice - Modern" (style=modern, lang=en)
├── Template: "Invoice - Classic" (style=classic, lang=en)
├── Template: "Factuur - Modern" (style=modern, lang=nl)
└── Template: "Factuur - Classic" (style=classic, lang=nl)
```

Selection logic will determine which variant to render based on context.

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
2. Create `package.json` with `@epistola/shared` as dependency
3. Create `tsconfig.json` extending `../shared/tsconfig.base.json`
4. Create `vite.config.ts` using `createBaseConfig` from shared
5. Create `build.gradle.kts` following the integration pattern
6. Add to `settings.gradle.kts`: `include(":modules:<name>")`
7. Implement mount function in `src/lib.tsx`

### Type Changes

When modifying shared types:
1. Update types in `modules/shared/src/types/`
2. Run `pnpm --filter @epistola/shared build`
3. Other modules will pick up changes via workspace links