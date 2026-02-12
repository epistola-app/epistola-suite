# Editor Refactor Findings

This document captures issues, risks, and improvement opportunities observed in the current `editor.html` + `headless-editor` + `vanilla-editor` refactor.

## Scope

- `apps/epistola/src/main/resources/templates/templates/editor.html`
- `modules/headless-editor/*`
- `modules/vanilla-editor/*`

---

## Top Priority Issues

### 1) Potential duplicate save execution in `mountEditorApp`

**Where**
- `modules/vanilla-editor/src/mount.ts`
- `modules/vanilla-editor/src/controllers/editor-controller.ts`

**Why this is a problem**
- Save is wired both via Stimulus action (`data-action="editor#save"`) and an extra explicit click listener in `mountEditorApp`.
- This can call `onSave` twice for a single user click depending on wiring order and controller state.

**Impact**
- Double network requests.
- Race conditions for save status and dirty state.

**Suggested fix**
- Keep one source of truth for save behavior.
- Prefer controller-driven save only; remove direct button listener in `mountEditorApp`.

---

### 2) Preview trigger only watches selected data example id, not test data edits

**Where**
- `modules/vanilla-editor/src/mount.ts`

**Why this is a problem**
- Debounced preview currently subscribes to `$template` and `$selectedDataExampleId`.
- If test data is edited directly (without changing selected example id), preview may not refresh.

**Impact**
- Stale preview output.

**Suggested fix**
- Also subscribe to test data changes (or an equivalent store signal) for preview scheduling.

---

### 3) Fallback toolbar catalog path is incomplete for built-ins

**Where**
- `modules/vanilla-editor/src/mount.ts` (`buildToolbarCatalogFallback`)

**Why this is a problem**
- Fallback relies on `configuredPlugins` metadata for toolbar overrides.
- Built-in blocks do not pass through `configuredPlugins`, so fallback cannot reflect all plugin-derived metadata consistently.

**Impact**
- UI metadata drift if headless catalog is unavailable or shape changes.

**Suggested fix**
- Prefer `getBlockCatalog()` always; avoid fallback where possible.
- If fallback remains, derive from headless runtime definitions only, not external config assumptions.

---

### 4) Runtime escape hatch (`__saveHandler`, `__veEditor`) is fragile

**Where**
- `modules/vanilla-editor/src/mount.ts`
- `modules/vanilla-editor/src/controllers/editor-controller.ts`

**Why this is a problem**
- Non-typed/private fields are injected on editor/shell objects.
- Controller has layered fallbacks (`runtimeByShell`, `__saveHandler`) creating implicit coupling.

**Impact**
- Harder maintenance and debugging.

**Suggested fix**
- Standardize runtime contract through one typed runtime registry (`WeakMap`) and remove object mutation escape hatches.

---

## Medium Priority Issues

### 5) Modal/forms and shell HTML assembled as large string template

**Where**
- `modules/vanilla-editor/src/mount.ts` (`buildEditorAppShell`)

**Why this is a problem**
- Huge string-based HTML is brittle, difficult to review/test, and easy to break semantically.

**Impact**
- Increased maintenance cost.

**Suggested fix**
- Move shell rendering to composable template helpers (uhtml templates or focused builders).
- Separate toolbar, preview, and each modal into dedicated modules.

---

### 6) `documentStyles` and `block styles` value handling is inconsistent

**Where**
- `modules/vanilla-editor/src/mount.ts` (modal save/load logic)

**Why this is a problem**
- Some values are treated as strings always, some as numeric+unit, and defaults are partly implicit.
- Color/default semantics are inconsistent (`#000000` special-cased in one place).

**Impact**
- Potential data normalization edge cases and hard-to-predict persistence behavior.

**Suggested fix**
- Extract style form serialization/parsing into pure utilities.
- Apply consistent default handling and unit policies.

---

### 7) Drag-blocking strategy is broad and could suppress unrelated UX

**Where**
- `modules/vanilla-editor/src/sortable-adapter.ts`
- `modules/vanilla-editor/src/controllers/text-block.ts`
- `modules/vanilla-editor/src/styles/editor.css`

**Why this is a problem**
- Global event blockers + pointer-events suppression can over-block interactions while dragging.
- Behavior works as protection, but may mask finer-grained drag/drop UX needs.

**Impact**
- Possible interaction regressions in complex nested editors.

**Suggested fix**
- Keep protections but narrow scope with stronger drag-origin checks.
- Add explicit tests for non-editor drop targets while drag is active.

---

### 8) Build tasks now run frontend build in Gradle compile path (good), but can increase CI time unexpectedly

**Where**
- `modules/headless-editor/build.gradle.kts`
- `modules/vanilla-editor/build.gradle.kts`

**Why this is a concern**
- `compileJava` now depends on frontend build copy step.
- Better correctness, but higher build time and tighter cross-tooling dependency.

**Impact**
- Slower local Java-only iteration.

**Suggested fix**
- Keep current correctness-first behavior.
- Consider optional profile/task split for developer workflows if build time becomes painful.

---

## Low Priority / Quality Improvements

### 9) `editor.html` now cleanly hosts the app, but lost explicit local debug panel

**Where**
- `apps/epistola/src/main/resources/templates/templates/editor.html`

**Why this is notable**
- Debug UI was removed from template (likely intentional), but no equivalent host toggle is visible in shell.

**Impact**
- Reduced ad-hoc observability for local debugging.

**Suggested fix**
- Add optional debug plugin/panel in `mountEditorApp` behind env flag.

---

### 10) New APIs are clearer, but migration compatibility shim is minimal

**Where**
- `modules/vanilla-editor/src/index.ts`
- `modules/vanilla-editor/src/types.ts`

**Why this is notable**
- `mountEditor`/old config types were replaced by `mountEditorApp`.
- Good direction, but no explicit deprecation shim for downstream callers.

**Impact**
- Abrupt migration friction for internal users.

**Suggested fix**
- Add temporary deprecated wrapper or migration docs section with before/after mapping.

---

### 11) Some E2E coverage is currently skipped around nested add/move flows

**Where**
- `modules/vanilla-editor/src/__e2e__/block-operations.spec.ts`
- `modules/vanilla-editor/src/__e2e__/block-rendering.spec.ts`
- `modules/vanilla-editor/src/__e2e__/undo-redo.spec.ts`

**Why this is notable**
- Critical nested interactions have `test.skip` entries.

**Impact**
- Lower confidence for regressions in exactly the refactored areas.

**Suggested fix**
- Re-enable with deterministic helper strategy (direct editor API only as last resort).
- Prioritize drag/drop and nested add-to-selected behavior.

---

## Headless Plugin Model Notes

### 12) Plugin validation is a good addition; error UX could be improved

**Where**
- `modules/headless-editor/src/editor.ts` (`validateBlockPlugin`)

**Why this is notable**
- Runtime validation is strict and descriptive (good).
- Could include richer context for host diagnostics (plugin source/module name).

**Suggested improvement**
- Add optional plugin `origin` metadata for error messages and logs.

---

### 13) Block definition + plugin pathways coexist; eventual consolidation desirable

**Where**
- `modules/headless-editor/src/editor.ts`
- `modules/headless-editor/src/blocks/plugins.ts`

**Why this is notable**
- Backward compatibility is handled, but multiple registration paths increase complexity.

**Suggested improvement**
- Long term, converge all runtime behavior on plugin contracts internally.

---

## Suggested Action Plan (Short)

1. Remove duplicate save wiring in `mountEditorApp` and keep controller/runtime single path.
2. Include test data changes in preview debounce triggers.
3. Replace shell string-template assembly with composable render units.
4. Remove `__saveHandler`/`__veEditor` escape hatches; keep typed runtime map only.
5. Re-enable skipped nested E2E cases for add/move/undo flows.

---

## Overall Assessment

The refactor direction is strong: host template simplified, headless pluginization is meaningful, and vanilla now owns its shell coherently. The main remaining risks are around runtime wiring consistency (save/preview), maintainability of very large shell setup logic, and temporary test coverage gaps in nested interactions.
