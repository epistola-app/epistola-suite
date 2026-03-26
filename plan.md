# Style Support Parity — Remaining Work

## Branch: `feature/style-support-parity-v2`

## What's Done

### Sizing system (merged to main via #240)
- `sp` unit: spacing scale based on multiples of theme's `spacingUnit` (default 4pt)
- Only 3 units: `pt`, `sp`, `mm` (page margins)
- `SpacingScale.kt` with `parseSp()` / `resolve()`
- `StyleApplicator` parses `sp`, `pt`, `mm`
- `RenderingDefaults` V1 with grid-aligned values
- Editor: number inputs for all sizes, sp/pt unit toggle
- Theme: `spacingUnit` field persisted + editable
- Contract: `spacingUnit` on ThemeDto, editor-model theme schema updated
- REST API: `spacingUnit` wired through DTOs
- Demo template: all px values converted to pt/sp
- Borders: `StyleApplicator` renders `borderWidth`/`borderStyle`/`borderColor`, compound shorthands (`borderTop: "2pt solid #333"`), `borderRadius`
- Page header/footer: configurable `height` prop (pt/sp)
- Image dimensions: pt/sp/% with unit selector

### This branch (3 commits)
1. **Liberation Serif + Mono fonts** — 8 TTF files, `FontFamily` enum, `resolveFontFamily()`, StyleApplicator resolves fontFamily/fontWeight/fontStyle together
2. **Per-side border properties** — `borderTopWidth`/`Style`/`Color` (×4 sides), per-corner `borderTopLeftRadius` etc., `letterSpacing` via `setCharacterSpacing()`
3. **Linking checkboxes + clear buttons** — "All" toggle on spacing inputs, per-node link state in inspector, × clear button on unit/color/spacing inputs

### Contract (`feature/styling-parity` branch, PR #5)
- `style-system.schema.json` + `default-style-system.json` added to editor-model
- Replaces old `style-registry.schema.json`
- Not yet released or consumed

## What's Left to Port

### Must have — real functionality gaps

#### 1. `style-values.ts` helpers (NEW file, ~150 lines)
Border and border-radius read/expand functions needed by the inspector:
- `readBorderFromStyles(styles)` → `BorderInputValue` (reads `borderTopWidth`, `borderTopStyle`, `borderTopColor` × 4 sides)
- `expandBorderToStyles(value, styles)` — writes `BorderInputValue` back to individual properties
- `readBorderRadiusFromStyles(styles)` → `BorderRadiusInputValue` (reads `borderTopLeftRadius` × 4 corners)
- `expandBorderRadiusToStyles(value, styles)` — writes back to individual properties
- Type definitions: `BorderSideValue`, `BorderInputValue`, `BorderRadiusInputValue`

**Source:** `modules/editor/src/main/typescript/engine/style-values.ts` from old branch. Adapt to pt/sp units.

#### 2. Border input component (`renderBorderInput`) (~200 lines in style-inputs.ts)
Per-side border editing: width + style + color for each side, with linking.
- `BorderSideInputValue { width, style, color }`
- `BorderInputValue { top, right, bottom, left }` (each is a `BorderSideInputValue`)
- `BorderLinkState` (same as `BoxLinkState` — All/Horizontal/Vertical)
- Linking: when "All" linked, editing any side updates all 4
- Clear button to remove all borders
- Width uses unit input (pt/sp), style uses select (none/solid/dashed/dotted), color uses color picker

**Source:** `renderBorderInput()` in old branch's `style-inputs.ts`.

#### 3. Border-radius input component (`renderBorderRadiusInput`) (~100 lines in style-inputs.ts)
Per-corner radius editing with "All" linking toggle.
- `BorderRadiusInputValue { topLeft, topRight, bottomRight, bottomLeft }`
- Single "All" checkbox (simpler than border's 3-mode linking)
- Clear button
- Each corner is a unit input (pt/sp)

**Source:** `renderBorderRadiusInput()` in old branch's `style-inputs.ts`.

#### 4. Wire border + radius inputs into EpistolaInspector
- Add `border` and `borderRadius` cases to `_renderStyleInput()`
- Add `_borderLinkStates` and `_borderRadiusLinkStates` Maps for per-node state
- Read values via `readBorderFromStyles()` / `readBorderRadiusFromStyles()`
- Write values via `expandBorderToStyles()` / `expandBorderRadiusToStyles()`
- Update `_handleNodeStyleChange()` to handle border/radius expansion

#### 5. Wire into PresetItem (theme editor)
- Same border/radius input rendering for theme block style presets
- Preset-scoped link state tracking

#### 6. Inspector CSS for border + radius inputs (~100 lines)
- `.style-border-side`, `.style-border-fields` layout
- `.style-border-width` (60px), `.style-border-style` (80px), `.style-border-color-picker` (28×28px)
- `.style-radius-corners`, `.style-radius-corner` layout

#### 7. Add `border` and `borderRadius` to style-registry.ts
The style registry currently has individual `borderWidth`/`borderStyle`/`borderColor`/`borderRadius` fields. Need to replace with composite `border` (control: 'border') and `borderRadius` (control: 'borderRadius') fields that map to the per-side/per-corner properties.

### Nice to have — can do later

- `width` property in the style inspector
- Theme editor layout improvements (2fr/3fr grid, scrollbars)
- Canvas CSS variable injection for typography preview
- More tests for style-inputs, style-values
- Universal component registry (components defined once, consumed by both editor + PDF renderers)
- StyleSystem migration (replace style-registry.ts with centralized JSON from contract — blocked on contract release)

## File Reference

Key files from old branch to reference:
- `git show feature/style-support-parity:modules/editor/src/main/typescript/engine/style-values.ts`
- `git show feature/style-support-parity:modules/editor/src/main/typescript/ui/inputs/style-inputs.ts`
- `git show feature/style-support-parity:modules/editor/src/main/typescript/ui/EpistolaInspector.ts`
- `git show feature/style-support-parity:modules/editor/src/main/typescript/theme-editor/sections/PresetItem.ts`
- `git show feature/style-support-parity:modules/editor/src/main/typescript/styles/inspector.css`
