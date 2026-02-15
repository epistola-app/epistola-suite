# Editor V2 Integration — Phase 7 + Phase 8

## Status: Done

## Context

The v2 editor (Lit + ProseMirror + headless engine) is feature-complete for its initial scope (Phases 0-6 done). The v1 editor (React + TipTap + Zustand) still serves the app. This change:

1. Switches the entire backend from the v1 `TemplateModel` (flat `blocks[]`) to the v2 `TemplateDocument` (normalized node/slot graph)
2. Rewrites PDF generation for node/slot traversal
3. Wires the v2 editor into the Thymeleaf host page
4. Deletes the v1 editor, vendor module, and import map

**Dropped for now:** schema editing, data example CRUD, theme selection UI (v2 does not have these yet).

---

## Steps

| Step | Description | Status |
|------|-------------|--------|
| 0 | Create this plan document | Done |
| 1 | Add v2 TemplateDocument/Node/Slot Kotlin data classes | Done |
| 2 | Switch domain layer to TemplateDocument | Done |
| 3 | Rewrite PDF generation for node/slot traversal | Done |
| 4 | Database migration + DemoLoader + tests | Done |
| 5 | Wire v2 editor in Thymeleaf + Gradle build | Done |
| 6 | Delete v1 React editor module | Done |
| 7 | Bundle vendor deps into schema-manager, delete vendor module | Done |
| 8 | Rename editor-v2 to editor | Done |
| 9 | Remove v1 model types, update documentation | Done |

## Commit Strategy

| Commit | Steps | What |
|--------|-------|------|
| 1 | 0 | docs: add editor v2 integration plan |
| 2 | 1 | feat: add v2 TemplateDocument/Node/Slot Kotlin data classes |
| 3 | 2-4 | feat!: switch backend from TemplateModel to TemplateDocument |
| 4 | 5 | feat: wire v2 editor in Thymeleaf + add to Gradle build |
| 5 | 6 | chore: delete v1 React editor module |
| 6 | 7 | chore: bundle vendor deps into schema-manager, delete vendor module |
| 7 | 8 | refactor: rename editor-v2 to editor |
| 8 | 9 | chore: remove v1 model types, update documentation |

Steps 2-4 are combined into one commit because intermediate states have failing tests.
