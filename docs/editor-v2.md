# Epistola Template Editor V2 – Implementation Plan (Aligned With Current Repo)

This document defines the step-by-step plan for building **editor-v2**, fully aligned with the current repository structure.

Key decisions already made:

- Schemas + domain live in `modules/template-model`
- No separation between TS and Kotlin domain models
- editor-v2 replaces older rewrite attempts
- JSON Schema (Draft 2020-12) is the single source of truth
- Publishing creates immutable versions
- Examples + dataSchema are shared across versions
- Themes provide defaults + block presets
- Plugins extend via registry, not via schema rewrites

---

# 1. Target Architecture Overview

```
modules/
  template-model      ← Canonical domain (schemas + types)
  editor-v2           ← New editor (engine + UI)
  epistola-core       ← Backend domain logic
  rest-api            ← API layer
```

editor-v2 will contain:

- Headless editor engine
- Registry system
- Theme resolver
- Validation (structural + semantic)
- UI (Tree, Canvas, Inspector, Preview)
- DnD
- ProseMirror integration
- Autosave + publish flow

---

# 2. Step 1 — Make template-model the Single Source of Truth

## 2.1 Folder structure

```
modules/template-model/
  schemas/
    template-document.schema.json
    template-shared.schema.json
    template-draft.schema.json
    template-version.schema.json
    theme.schema.json
    style-registry.schema.json
    component-manifest.schema.json
    plugin-manifest.schema.json
  src/main/kotlin/
  src/ts/
  package.json
```

## 2.2 Responsibilities

This module owns:

- Canonical JSON Schemas
- Generated TypeScript types
- Kotlin migration helpers
- Shared validation rule codes

Both backend and frontend must depend on this module.

---

# 3. Step 2 — Define Domain Model Split

## 3.1 TemplateShared (not versioned)

- templateId
- name
- defaultThemeId
- dataSchema
- examples[]

## 3.2 TemplateDocument (versioned)

- modelVersion
- root
- nodes
- slots
- themeRef (inherit | override)
- pageSettingsOverride
- documentStylesOverride

## 3.3 TemplateDraft

- templateId
- document

## 3.4 TemplateVersion

- templateId
- version
- document (immutable snapshot)

## 3.5 Theme

- id
- name
- defaults (page + document styles)
- blockStylePresets

---

# 4. Step 3 — Generate Types From Schemas

Inside template-model:

1. Add pnpm script:
   - generate TS types from JSON schema
2. Export:
   - generated types
   - raw schema JSON (for Ajv)
3. Backend loads schemas from same folder for validation

---

# 5. Step 4 — Create editor-v2 Module

```
modules/editor-v2/
  src/
    engine/
    registry/
    preview/
    ui/
  package.json
```

Dependencies:

- template-model
- Ajv
- jsonata
- ProseMirror
- @atlaskit/pragmatic-drag-and-drop
- nanoid
- (optional) Immer
- (optional) Lit

---

# 6. Step 5 — Build Headless Engine

## 6.1 Engine Responsibilities

- Maintain TemplateDocument state
- Maintain derived indexes (parentByNodeId, slot graph)
- Dispatch commands
- Undo/redo
- Structural validation (Ajv)
- Semantic validation (rules)
- Theme resolution
- Style resolution
- Expression evaluation
- Provide selectors for UI

## 6.2 Core Commands

- InsertNode
- RemoveNode
- MoveNode
- UpdateNodeStyles
- SetStylePreset
- UpdateExpression
- SetThemeOverride
- UpdatePageSettingsOverride
- UpdateDocumentStylesOverride

All mutations go through dispatch().

---

# 7. Step 6 — Structural + Semantic Validation

## Structural
- Ajv validates against template-document.schema.json

## Semantic
- Slot references valid
- No cycles
- Header/footer singleton
- Header/footer only at root
- Child compatibility (registry-based)
- Style keys known + allowed
- Preset applicable to block type
- Plugin props valid (if schema exists)

Backend must re-run semantic validation before publish.

---

# 8. Step 7 — Registry System

## 8.1 Style Registry

Defines:

- style key
- type (length, color, enum, etc.)
- group (Typography, Spacing, etc.)
- allowed values/units

## 8.2 Component Registry

Each block defines:

- type
- slots exposed
- allowed children rules
- style policy
- inspector configuration

Core blocks:

- text
- container
- columns
- table
- conditional
- loop
- pagebreak
- pageheader
- pagefooter
- plugin

---

# 9. Step 8 — Theme Resolution

Resolution order:

1. Theme defaults
2. Document overrides
3. Preset styles
4. Inline styles

Expose:

- resolvePageSettings()
- resolveDocumentStyles()
- resolveNodeStyles(nodeId)

Publishing policy decision:
- Recommended: snapshot resolved defaults into TemplateVersion

---

# 10. Step 9 — Preview System

Preview pipeline:

1. Select example
2. Evaluate JSONata expressions
3. Expand loops
4. Apply conditionals
5. Render resolved styles

Preview must not mutate stored document.

---

# 11. Step 10 — UI (editor-v2)

Layout:

- Left: Tree
- Center: Canvas (Design / Preview toggle)
- Right: Inspector
- Top: Toolbar

UI rules:

- Never mutate document directly
- Always call engine.dispatch()

---

# 12. Step 11 — Drag & Drop

- Use pragmatic-drag-and-drop
- Drop targets defined as (slotId, index)
- Validate drop via registry rules
- Dispatch MoveNode or InsertNode

---

# 13. Step 12 — Rich Text (ProseMirror)

- Only active inside TextNode
- Commit content on debounce or blur
- Separate undo stack while focused
- Engine undo outside text editing

---

# 14. Step 13 — Autosave + Publish

Autosave:

- Debounced PUT draft
- Optional IndexedDB local backup

Publish:

1. Run structural validation
2. Run semantic validation
3. Snapshot document
4. Create new TemplateVersion

---

# 15. Step 14 — Plugin Support

Plugin provides:

- manifest
- block definitions
- props schema
- style policy
- optional migrations

Unknown plugin:

- Render placeholder
- Preserve props
- Warn but do not destroy data

---

# 16. Milestones

Milestone 1:
- Schemas finalized
- Type generation working

Milestone 2:
- Engine supports insert/move/remove + undo

Milestone 3:
- Minimal UI with tree + canvas

Milestone 4:
- Styles + presets working

Milestone 5:
- Rich text integrated

Milestone 6:
- Preview system working

Milestone 7:
- Publish flow complete

Milestone 8:
- Plugin extension proof of concept

---

# 17. Definition of Done

Editor-v2 must support:

- Block DnD
- Sidebar style editing (restricted by policy)
- Tree navigation
- Theme presets
- JSONata-based conditionals + loops
- Rich text editing
- Undo/redo
- Autosave
- Immutable publishing
- Plugin extensibility

All using the single canonical schema in modules/template-model.
