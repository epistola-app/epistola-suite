# Editor V2 — Implementation Plan

## Progress Summary

| Phase | Status | Notes |
|-------|--------|-------|
| **0. Foundation** | **Mostly done** | Schemas, types, scaffold complete. Kotlin data classes pending. |
| **1. Headless Engine** | **Done** | All commands, undo/redo, registry, 45 tests passing. |
| **2. Minimal UI** | **Done** | Shell, tree, canvas, palette, inspector, toolbar, DnD (canvas + tree). |
| **2.x Design System** | **Done** | Shared `modules/design-system/` extracted (not in original plan). |
| **3. Styles + Themes** | **Done** | Style registry, resolver, engine integration, inspector UI, canvas rendering. |
| **4. Rich Text** | **Done** | ProseMirror integration, expression chips, bubble menu, input rules. |
| **5. Preview** | **Done** | PDF preview panel with resize, toggle, debounced fetch, stub callback. |
| **6. Save + Autosave** | **Done** | SaveService, autosave (3s debounce), Ctrl+S, dirty tracking, beforeunload. |
| **7. Backend Adaptation** | **Done** | Domain layer, PDF generation, Thymeleaf page all switched to v2. |
| **8. Cleanup** | **Done** | V1 editor + vendor module deleted. Editor-v2 renamed to editor. V1 types removed. |

---

## Context

The current editor (React 19 + TipTap + Zustand + dnd-kit) has two structural problems:

1. **Data model limitations**: The flat `blocks[]` with recursive nesting forces every operation (add, move, delete) to handle multiple code paths — root blocks, `children[]`, column composite IDs (`blockId::columnId`), table composite IDs (`blockId::rowId::cellId`). See `editorStore.ts:199-353` for the pain. Every new container type would need more composite ID handling.

2. **Extensibility gaps**: No plugin system, no component registry, no way to add custom block types without modifying core code. The sealed block type hierarchy on both frontend and backend makes extension painful.

Additionally: **React is being dropped**. The rest of the app uses Thymeleaf + HTMX (standards-based). The editor is the only React component, pulling in React + 20 Radix UI packages + Zustand + Immer via a vendor module/import map.

**Strategy**: Big bang replacement. Build `modules/editor-v2/` in parallel, switch over when ready, then delete the old editor. No backwards compatibility needed (pre-production).

---

## Architecture Decisions

### 1. UI Framework: Lit (Web Components)

**Recommendation: Lit**, for these reasons specific to this project:

- **Clean embedding**: The editor mounts in a Thymeleaf page. Custom elements (`<epistola-editor>`) provide the strongest mount/unmount contract — it's a web standard, not a framework lifecycle.
- **Eliminates vendor module**: No React, Radix, Zustand to bundle. Lit is ~7KB. The entire `modules/vendor/` module and import map complexity goes away.
- **ProseMirror compatibility**: ProseMirror manages its own DOM. React fights this (TipTap exists to solve that). Lit leaves ProseMirror alone — mount it in a `<div>` and it works.
- **Headless engine fit**: Most complexity lives in the engine (framework-agnostic TS). The UI layer is thin: subscribe to state, render, dispatch commands. Lit's reactive properties handle this naturally.

**Fallback**: If Lit proves too verbose after Phase 2 (minimal UI), Svelte 5 is the alternative — the headless engine is framework-agnostic so only the UI layer would change.

### 2. Styling: Vanilla CSS with Shared Design System

Modern CSS (2026) provides everything needed without a utility framework:

- **CSS Nesting** — component-scoped styles without BEM or preprocessors
- **`@layer`** — cascade control for theme defaults vs. component styles vs. overrides
- **`@scope`** — DOM-scoped styling without Shadow DOM
- **Container Queries** — responsive inspector/canvas panels based on available space
- **`:has()`** — parent selectors for contextual styling (e.g., selected node states)
- **Custom Properties** — design tokens for theme colors, spacing, typography
- **`oklch()`** — perceptually uniform color manipulation for theme palettes
- **Anchor Positioning** — tooltips, dropdowns, context menus without JS positioning libraries
- **Popover API** — native modals/dialogs without Radix UI
- **View Transitions** — smooth panel/mode transitions

This eliminates Tailwind, PostCSS, and all CSS tooling overhead.

**Design system module** (`modules/design-system/`): Shared CSS consumed by both the editor (via Vite `@design` alias) and the main app (via Gradle copy + Thymeleaf fragment). Contains:
- `tokens.css` — `:root` custom properties (colors, typography, spacing, radii, shadows)
- `base.css` — `@layer base` resets (box-sizing, button/input defaults)
- `components.css` — `@layer components` shared UI patterns (`.panel-*`, `.ep-input`, `.ep-select`, `.ep-checkbox`, `.ep-btn-danger`)

Editor-specific styles live in `modules/editor-v2/src/main/typescript/styles/` as 6 component files (editor-layout, toolbar, tree, canvas, palette, inspector). `editor.css` is an import-only entry point with `@layer` ordering.

### 3. Node/Slot Data Model

Replace the recursive `blocks[]` with a normalized node/slot graph:

```typescript
interface TemplateDocument {
  modelVersion: 1;
  root: NodeId;                    // ID of the root node
  nodes: Record<NodeId, Node>;     // Flat map of all nodes
  slots: Record<SlotId, Slot>;     // Flat map of all slots
  themeRef: ThemeRef;
  pageSettingsOverride?: PageSettings;
  documentStylesOverride?: DocumentStyles;
}

interface Node {
  id: NodeId;
  type: string;                    // "text" | "container" | "columns" | etc.
  slots: SlotId[];                 // Ordered slot references owned by this node
  styles?: Record<string, unknown>;
  stylePreset?: string;
  props?: Record<string, unknown>; // Type-specific (content, expression, columns, etc.)
}

interface Slot {
  id: SlotId;
  nodeId: NodeId;                  // Parent node that owns this slot
  name: string;                    // "children", "column-0", "cell-0-1", "body"
  children: NodeId[];              // Ordered child node IDs
}

type ThemeRef =
  | { type: "inherit" }
  | { type: "override"; themeId: string };
```

**Block type → slot mapping:**

| Node type | Slots | Props |
|-----------|-------|-------|
| `text` | 0 | `{ content: TipTapJSON }` |
| `container` | 1: `children` | `{}` |
| `columns` | N: `column-0` ... `column-N` | `{ columns: { size: number }[], gap?: number }` |
| `table` | R×C: `cell-{r}-{c}` | `{ columnWidths?, borderStyle?, rows: { isHeader? }[] }` |
| `conditional` | 1: `body` | `{ condition: Expression, inverse?: boolean }` |
| `loop` | 1: `body` | `{ expression: Expression, itemAlias, indexAlias? }` |
| `pagebreak` | 0 | `{}` |
| `pageheader` | 1: `children` | `{}` |
| `pagefooter` | 1: `children` | `{}` |
| `plugin` | Defined by manifest | `{ pluginId, pluginVersion, data }` |

**Why this is better**: Every insert/move/remove is the same operation: update `slot.children` arrays. No composite IDs, no type-specific traversal. O(1) node lookup. Parent tracking via derived index. Cycle detection by walking the parent chain.

### 4. Library Choices

| Choice | Decision | Rationale |
|--------|----------|-----------|
| Rich text | **TipTap core** (keep, drop `@tiptap/react`) | TipTap core is framework-agnostic. ExpressionNode extension ports directly. Backend `TipTapConverter.kt` unchanged. |
| DnD | **pragmatic-drag-and-drop** | Framework-agnostic (DOM events). Drop targets map to `(slotId, index)`. |
| State | **Headless engine + Lit reactive properties** | Engine owns state, exposes subscribe/notify. No Zustand, no Immer. |
| Validation | **Ajv** (structural) + custom rules (semantic) | Standard JSON Schema validation + registry-based rules. |
| IDs | **nanoid** | Lightweight, URL-safe, fast. |
| CSS | **Vanilla CSS** + shared design system | Modern CSS features (nesting, layers, scope, custom properties). Shared `modules/design-system/` for app-wide consistency. |

---

## Implementation Phases

### Phase 0: Foundation (Schemas + Types) — MOSTLY DONE

**0.1 — Create `modules/editor-v2/` scaffold** ✅
- `package.json`, `tsconfig.json`, `vite.config.ts` (library mode, ESM output)
- Lit, vitest, nanoid dependencies
- Wired into pnpm workspace

**0.2 — Define JSON Schemas in `modules/template-model/`** ✅
- JSON Schema Draft 2020-12 files in `schemas/`:
  - `template-document.schema.json`, `template-shared.schema.json`
  - `theme.schema.json`, `style-registry.schema.json`, `component-manifest.schema.json`

**0.3 — Type generation pipeline** ✅
- `json-schema-to-typescript` generates TS interfaces from schemas
- Generated types in `modules/template-model/generated/`
- `editor-v2` depends on `@epistola/template-model` workspace package
- Hand-written `ts/model.ts` re-exports with branded NodeId/SlotId types

**0.4 — Kotlin data classes for V2 model** ⬜ NOT STARTED
- Replace `TemplateModel.kt` with `TemplateDocument`, `Node`, `Slot`, `ThemeRef`
- Jackson `@JsonTypeInfo` on Node for type discrimination
- Round-trip serialization tests

### Phase 1: Headless Engine — DONE ✅

**1.1 — Engine core** ✅
- `EditorEngine` class: state, derived indexes (`parentByNodeId`, `slotsByNodeId`), subscribe/notify
- Deep-freeze state to prevent accidental mutation

**1.2 — Command dispatch** ✅
- Commands: `InsertNode`, `RemoveNode`, `MoveNode`, `UpdateNodeProps`, `UpdateNodeStyles`, `SetStylePreset`, `UpdateDocumentStyles`, `UpdatePageSettings`
- Each command produces an inverse for undo
- Validation includes cycle detection, parent-child constraints, duplicate prevention

**1.3 — Undo/redo** ✅
- `UndoStack` with 100-entry depth
- Redo clears on new dispatch

**1.4 — Component registry** ✅
- 10 built-in types: root, text, container, columns, table, conditional, loop, pagebreak, pageheader, pagefooter
- Slot templates, allowed children, style policies, inspector config

**Verification**: 45 engine tests passing (`pnpm --filter @epistola/editor-v2 test`)

### Phase 2: Minimal UI — PARTIALLY DONE

**2.1 — Editor shell** ✅
- `<epistola-editor>` Lit element as root entry point
- `mountEditor()` API (same pattern as V1)
- Layout: left (palette), center-left (tree), center (canvas), right (inspector), top (toolbar)
- All UI components use Light DOM (no Shadow DOM) for shared CSS

**2.1b — Design system extraction** ✅ (added during implementation)
- Shared `modules/design-system/` with `tokens.css`, `base.css`, `components.css`
- Editor-specific styles in `styles/` directory (6 component CSS files)
- `editor.css` is import-only entry point
- Vite `@design` alias resolves to `../design-system`
- Gradle `copyDesignSystem` task copies to Spring Boot static resources
- `fragments/styles.html` Thymeleaf fragment for centralized CSS includes
- Main app `main.css` rewritten to use `var(--ep-*)` design tokens
- Generic class renames: `.inspector-input` → `.ep-input`, `.inspector-select` → `.ep-select`, `.inspector-checkbox` → `.ep-checkbox`, `.inspector-delete-btn` → `.ep-btn-danger`

**Bundle size**: 8.51 kB CSS (1.99 kB gzipped), 55.05 kB JS (13.75 kB gzipped)

**2.2 — Tree panel** ✅
- `<epistola-tree>`: renders node hierarchy from engine state
- Click-to-select, highlight selected, node type icons

**2.3 — Canvas (structural)** ✅
- `<epistola-canvas>`: renders nodes as structural blocks (type label + placeholder)
- Click-to-select, highlight selected, slot drop zone placeholders
- Columns layout, page break visualization

**2.4 — Block palette + DnD** ⬜ PARTIAL
- `<epistola-palette>`: list insertable types from registry, grouped by category ✅
- Click-to-insert working ✅
- `@atlaskit/pragmatic-drag-and-drop` integration: ⬜ NOT STARTED
  - Palette items = drag sources, slot zones = drop targets, tree items = both
  - On drop: dispatch `InsertNode` or `MoveNode`

**2.5 — Basic inspector** ✅
- `<epistola-inspector>`: shows editable fields based on registry `inspectorConfig`
- Property editors: text, number, boolean (checkbox), select, expression (mono input)
- Style preset field
- Delete block button
- Dispatches `UpdateNodeProps` on change

### Phase 3: Styles + Themes — NOT STARTED

**3.1 — Style + theme resolution**
- Style registry: define style properties, groups, allowed values
- Theme resolver cascade: theme defaults → document overrides → preset → inline
- `resolvePageSettings()`, `resolveDocumentStyles()`, `resolveNodeStyles(nodeId)`

**3.2 — Style editor in inspector**
- Color picker, spacing input, font selector, border controls
- Respect style policy per block type
- Show inherited vs. overridden indicators

### Phase 4: Rich Text — DONE ✅

**4.1 — ProseMirror integration** ✅
- Direct ProseMirror (no TipTap wrapper) for rich text editing in text blocks
- Full JSON compatibility with existing backend converter
- ExpressionNode extension ported as inline chips
- Debounced content sync with engine, undo delegation

**4.2 — Text toolbar (bubble menu)** ✅
- Floating bubble menu: bold, italic, underline, strikethrough, headings, lists, expressions
- Heading input rules (# , ## , ### )
- Expression editor dialog with field path autocomplete

### Phase 5: Preview — DONE ✅

**5.1 — PreviewService** ✅
- Pure TS class: debounced scheduling, AbortController management, blob URL lifecycle
- State machine: idle → loading → success/error
- 12 unit tests covering all transitions

**5.2 — Preview panel** ✅
- `<epistola-preview>`: subscribes to `doc:change` / `example:change`, renders iframe with blob URL
- `<epistola-resize-handle>`: pointer-drag sets `--ep-preview-width`, persisted to localStorage
- Toggle button in toolbar, open/close state persisted to localStorage
- `onFetchPreview` callback in `EditorOptions` — host page owns HTTP, editor owns lifecycle
- Stub callback wired in `editor.html` (calls backend preview endpoint; real PDF depends on Phase 7)

### Phase 6: Save + Publish — NOT STARTED

**6.1 — Autosave**
- Debounced save (3s inactivity) via callback from host page
- Save status indicator

**6.2 — Validation + Publish flow**
- Structural validation (Ajv) + semantic validation (registry rules)
- Show errors in UI, block publish if invalid
- Backend creates immutable `TemplateVersion` snapshot

### Phase 7: Backend Adaptation — NOT STARTED

**7.1 — Update backend domain for node/slot model**
- `TemplateVersion.templateModel` type → `TemplateDocument`
- Update all commands/queries: `GetEditorContext`, `UpdateDraft`, `CreateVersion`, `PublishVersion`
- Update `ThemeStyleResolver`
- Flyway migration: `TRUNCATE template_versions CASCADE;`
- Update `DemoLoader` for V2 format

**7.2 — Rewrite PDF generation for node/slot traversal**
- `DirectPdfRenderer`: traverse `root → slot → children → slot → ...` instead of `blocks[]`
- Each block renderer: `Block` → `Node` + `TemplateDocument`
- `RenderContext` gains `TemplateDocument` reference
- `TipTapConverter.kt` unchanged (same TipTap JSON format)

**7.3 — Update Thymeleaf host page**
- `editor.html`: load editor-v2 bundle, simplified import map, updated `mountEditor()` call

### Phase 8: Cleanup — NOT STARTED

- Delete `modules/editor/` (old React editor)
- Delete React-related vendor dependencies
- Clean up import map

### Phase 9: Plugin PoC (if time permits) — NOT STARTED

- Plugin manifest format + registry extension
- Custom block type registration
- Placeholder rendering for unknown plugins

---

## Backend Files Affected

| File | Change |
|------|--------|
| `modules/template-model/.../TemplateModel.kt` | Rewrite: node/slot model |
| `modules/epistola-core/.../templates/model/TemplateVersion.kt` | Type change |
| `modules/epistola-core/.../templates/queries/GetEditorContext.kt` | Deserialize new model |
| `modules/epistola-core/.../templates/commands/versions/UpdateDraft.kt` | Type change |
| `modules/epistola-core/.../templates/commands/versions/CreateVersion.kt` | Type change |
| `modules/epistola-core/.../themes/ThemeStyleResolver.kt` | Accept new model |
| `modules/generation/.../pdf/DirectPdfRenderer.kt` | Rewrite traversal |
| `modules/generation/.../pdf/RenderContext.kt` | Add document reference |
| `modules/generation/.../pdf/*BlockRenderer.kt` | `Block` → `Node` |
| `modules/generation/.../TipTapConverter.kt` | No change |
| `apps/epistola/.../templates/templates/editor.html` | Load V2 bundle |
| `db/migration/V__template_model_v2.sql` | Truncate version data |

---

## Verification

After each phase, verify:

- **Phase 0**: `pnpm run generate:types` produces valid TS, Kotlin serialization tests pass (`./gradlew test --tests *TemplateDocument*`)
- **Phase 1**: Engine unit tests pass (`pnpm --filter @epistola/editor-v2 test`), all commands + undo/redo tested ✅
- **Phase 2**: Editor mounts in a test HTML page, tree/canvas/inspector render, DnD creates/moves nodes
- **Phase 3**: Styles apply on canvas, theme resolution correct
- **Phase 4**: Text editing works in canvas, expressions render as chips
- **Phase 5**: Preview renders with test data, expressions evaluated
- **Phase 6**: Save persists to backend, publish creates version
- **Phase 7**: `./gradlew test` passes (all backend tests), PDF generation works with V2 model
- **Phase 8**: Old editor removed, `pnpm build && ./gradlew build` succeeds
