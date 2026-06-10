# Inline Style Hygiene

The project enforces a strict no-inline-styles policy across all surfaces: Thymeleaf templates, TypeScript components, and CSS files. An automated test (`InlineStyleHygieneTest`) runs in CI and **fails the build** on any violation.

The design system (`modules/design-system/components.css`) provides a set of reusable classes for common patterns: buttons (`.ep-btn`, `.ep-btn-primary`), form controls (`.ep-label`, `.ep-input`, `.ep-select`, `.ep-textarea`, `.ep-checkbox`), tables (`.ep-table`, `.ep-table-truncate`), code (`.ep-code`), dialogs (`.ep-dialog`), spacing (`.ep-mt-*`, `.ep-mb-*`, `.ep-p-*`), typography (`.ep-text-*`, `.ep-font-*`), and display (`.ep-inline`). Use these when they fit.

For app-specific styling that the design system doesn't cover, add contextual or page-scoped classes to corresponding folder in `apps/epistola/src/main/resources/static/css/templates`. Do not add template-local `<style>` blocks.

## Quick Reference

| What                        | Allowed Pattern                                                                              | Banned Pattern                                                |
| --------------------------- | -------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| Static presentation         | Design-system class or contextual CSS class                                                  | `style="color: red"`                                          |
| App template CSS placement  | Corresponding app stylesheet                                                                 | Template-local `<style>` block                                |
| State-driven styling        | Data attribute + CSS `&[data-*]` or flat selector                                            | `classList.add('active')`                                     |
| Computed values (pixel)     | In TS: `style="--ep-*: ${val}"` + `calc()` in CSS; In HTML: `th:style="'--ep-*: ' + ${val}"` | `style="padding-left: ${d}px"` or any raw presentation inline |
| Hidden elements             | `data-hidden="true"` + CSS `[data-hidden='true']`                                            | `classList.add('hidden')` or `style.display = 'none'`         |
| Runtime display toggles     | `element.dataset.hidden = 'true'/'false'` + CSS                                              | `element.style.display = 'none'/'block'/'flex'`               |
| Runtime interaction state   | `document.body.dataset.dragging = 'true'` + CSS `[data-dragging]`                            | `document.body.style.cursor` or `userSelect`                  |
| Static sizing in JS         | CSS class or CSS custom property                                                             | `element.style.maxWidth` / `style.height` / `style.width`     |
| Design-system tokens        | Use existing token directly                                                                  | `var(--ep-fake-75)` or `var(--ep-gray-50, #f9fafb)`           |
| Conditional Thymeleaf class | `th:classappend="${active ? ' active' : _}"`                                                 | `style="..."` or duplicate `class` attrs                      |
| Conditional Thymeleaf style | `th:style="'--ep-foo: ' + ${val}"`                                                           | `th:style="val ? 'display:block' : 'display:none'"`           |

## The Ten Checks

### 1. No `style="..."` in HTML templates

All inline `style` attributes in Thymeleaf templates must be replaced with CSS classes from the design system or app stylesheet.

```html
<!-- BAD -->
<div style="margin-top: 12px; font-weight: 600;">Title</div>

<!-- GOOD — design-system class -->
<div class="ep-mt-3 ep-font-semibold">Title</div>

<!-- GOOD — contextual class for app-specific style -->
<div class="template-settings-title">Title</div>
```

```css
.template-settings-title {
  margin-top: var(--ep-space-3);
  font-weight: 600;
}
```

### 2. No `style="..."` in TypeScript (static)

Static `style="..."` in Lit template literals must use CSS classes instead. Dynamic styles (containing `${}`) are exempt — they may carry CSS custom properties.

```typescript
// BAD
html`<div style="font-size: 14px; color: #666;">content</div>`;

// GOOD — CSS class
html`<div class="inspector-muted-text">content</div>`;

// GOOD — CSS custom property carrier (dynamic, exempted)
html`<div style="--ep-depth: ${depth}">content</div>`;
```

### 3. No duplicate `class` attributes

Thymeleaf's XML parser rejects elements with two `class` attributes. Never produce `class="..." class="..."`.

```html
<!-- BAD — Thymeleaf responds with 500 -->
<div class="foo" class="bar">
  <!-- GOOD — single class attribute -->
  <div class="foo bar"></div>
</div>
```

### 4. Design-system tokens must exist and be used consistently

Never invent new token values or use fallbacks. Every `--ep-*` token must be an exact match from the design system (`modules/design-system/tokens.css`).

```css
/* BAD — made-up token that doesn't exist */
background: var(--ep-gray-75);

/* BAD — fallback value instead of using the token directly */
background: var(--ep-gray-50, #f9fafb);

/* BAD — derived value that falls between two existing tokens */
padding: calc(var(--ep-space-1) * 1.5);
```

```css
/* GOOD — use existing token directly */
background: var(--ep-gray-50);

/* GOOD — pick the closest existing token */
padding: var(--ep-space-1-5);
```

The design system has a **discrete** scale, not a continuous one. There is no `gray-75` between `gray-50` and `gray-100`. There is no `18px` space token — pick the closest existing token on the discrete scale. The same rule applies to colors, spacing, fonts, radii, shadows, and z-indices. If the token you want doesn't exist, you are either picking a wrong value or you need to add a new token to `tokens.css`.

**What this check covers:** This rule is enforced by the test's no-fallback regex (`var(--ep-*, <something>)`) but the intent is broader — it's about token consistency. The test catches the fallback case; human review catches the made-up-token case.

### 5. No `th:style` without `--ep-*`

The `th:style` attribute is only allowed for CSS custom property carriers:

```html
<!-- BAD — raw presentation in th:style -->
<div th:style="'color: ' + ${valid ? 'green' : 'red'}"></div>

<!-- GOOD — CSS custom property carrier -->
<div th:style="'--ep-bar-width: ' + ${percentage} + '%'"></div>

<!-- GOOD — th:classappend for conditional styling -->
<div th:classappend="${valid ? ' active' : _}"></div>
```

For visibility in server-rendered UI, prefer `data-hidden="true|false"` over class-based hidden toggles:

```html
<!-- BAD -->
<div th:classappend="${hasCredential} ? '' : ' ep-hidden'" class="catalog-auth-credential"></div>

<!-- GOOD -->
<div class="catalog-auth-credential" data-hidden="true"></div>
```

```css
.catalog-auth-credential[data-hidden="true"] {
  display: none;
}
```

### 6. No `var(--ep-*, <fallback>)` in TypeScript string literals

The same no-fallback rule applies to JavaScript string literals:

```typescript
// BAD
const color = valid ? "var(--ep-green-600, #16a34a)" : "var(--ep-destructive, #dc2626)";

// GOOD — use a CSS class or data attribute
element.dataset.validity = valid ? "valid" : "invalid";
```

### 7. No runtime `style.color` / `style.background` / `style.borderColor`

```typescript
// BAD
validityEl.style.color = "var(--ep-destructive, #dc2626)";

// GOOD — data attribute + CSS
validityEl.dataset.validity = "invalid";
```

**Exemption:** Content-driven colors from ProseMirror marks (e.g., `el.style.color = color` where `color` comes from `mark.attrs?.color`) are allowed because the color comes from the document model, not from a hardcoded value.

### 8. No `classList.add/remove/toggle/contains`

```typescript
// BAD
element.classList.add("active");
element.classList.remove("hidden");
element.classList.toggle("selected", isSelected);
element.classList.contains("hidden");

// GOOD
element.dataset.active = "true";
element.dataset.hidden = "false";
element.dataset.selected = isSelected ? "true" : "false";
element.dataset.hidden === "true";
```

### 9. No runtime `style.display =` assignments

Runtime display toggles must use the `data-hidden` attribute pattern instead of directly setting `style.display`:

```typescript
// BAD
menuEl.style.display = "none";
menuEl.style.display = "flex";
editorEl.style.display = "block";

// GOOD — Lit template
html`<div ?data-hidden=${!visible}>content</div>`;

// GOOD — runtime DOM
menuEl.dataset.hidden = "true";
menuEl.dataset.hidden = "false";
```

```css
/* CSS for runtime toggles */
.pm-bubble-menu[data-hidden="true"] {
  display: none;
}
```

### 10. No runtime `style.cursor` / `style.userSelect` / `style.maxWidth` / static `style.height` / `style.width`

Use body data attributes for interaction state and CSS classes for static sizing:

```typescript
// BAD
document.body.style.cursor = "col-resize";
document.body.style.userSelect = "none";
dialog.style.maxWidth = "min(960px, 90vw)";
editorEl.style.height = "100%";

// GOOD — body data attribute for interaction state
document.body.dataset.dragging = "resize-handle";
delete document.body.dataset.dragging;

// GOOD — CSS class for dialog sizing
dialog.className = "stencil-picker-dialog stencil-picker-dialog--definitions";
```

```css
/* Editor interaction state */
body[data-dragging] {
  user-select: none;
}
body[data-dragging="resize-handle"] {
  cursor: col-resize;
}

/* Dialog sizing variant */
.stencil-picker-dialog--definitions {
  max-width: min(960px, 90vw);
}
```

**Exemptions:** Content-driven positioning from floating-ui (`computePosition` → `style.left`/`top`), CSS custom property carriers (`style.setProperty('--ep-*', ...)`), and textarea auto-resize (`style.height = 'auto'` / `scrollHeight`) are allowed as computed values.

## Correct Patterns

### Static presentation → design-system or contextual class

Use design-system classes (`ep-mt-*`, `ep-text-*`, `ep-btn`, etc.) when they fit the need. For app-specific styling not covered by the design system, add a contextual class to corresponding CSS file.

```html
<!-- GOOD — design-system class -->
<span class="ep-text-sm ep-text-destructive ep-mt-1">Error message</span>

<!-- GOOD — contextual class for page/template-specific style -->
<div class="template-settings-description">Description</div>
```

### App template CSS placement

Keep app-specific CSS in the shared stylesheet instead of embedding `<style>` elements in Thymeleaf templates.

```html
<!-- BAD -->
<style>
  .consumer-toolbar {
    display: flex;
  }
</style>
```

```css
/* GOOD — apps/epistola/src/main/resources/static/css/main.css */
.consumer-toolbar {
  display: flex;
}
```

### State-driven styling → data attributes

Replace `classList.add/remove/toggle/contains` with `element.dataset.*`:

```typescript
// Before
card.classList.add("selected");
errorEl.classList.remove("hidden");
cell.classList.toggle("active", condition);

// After
card.dataset.selected = "true";
errorEl.dataset.hidden = "false";
cell.dataset.active = condition ? "true" : "false";
```

CSS selectors for data attributes can use either nesting (in editor component stylesheets) or flat selectors (in app stylesheet):

```css
/* Editor component stylesheet — nested */
.stencil-picker-card {
  &[data-selected="true"] {
    border-color: var(--ep-primary);
    box-shadow: 0 0 0 1px var(--ep-primary);
  }

  &[data-disabled="true"] {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

/* App stylesheet — flat (equally acceptable) */
.version-comparison-loading[data-hidden="true"] {
  display: none;
}
```

### Computed values → CSS custom properties

When a component needs to set a computed size, indent, or dimension, use a CSS custom property on the element as a data carrier, then compute with `calc()` in CSS:

```typescript
// In template
html`<div style="--ep-tree-depth: ${depth}" class="tree-node">...</div>`;
```

```css
/* In CSS */
.tree-node {
  --ep-tree-depth: 0;
  padding-left: calc(var(--ep-tree-depth) * 16px);
}
```

The CSS default (`--ep-tree-depth: 0`) ensures the property is always defined. The inline `style` carries only the data override.

### Hidden elements

Use `data-hidden="true"` + CSS `[data-hidden='true'] { display: none; }`:

```typescript
// Template — initially hidden
html`<div id="create-error" class="stencil-picker-error" data-hidden="true"></div>`;

// JS — toggle visibility
errorEl.dataset.hidden = "true"; // hide
errorEl.dataset.hidden = "false"; // show
```

```css
/* Editor component — nested */
.stencil-picker-error {
  &[data-hidden="true"] {
    display: none;
  }
}

/* App stylesheet — flat selector */
.version-comparison-loading[data-hidden="true"] {
  display: none;
}
```

## Running the Test

```bash
# Run all 10 checks
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*"

# Run a single check (match on test method name fragment)
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*display*"
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*positioning*"
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*classList*"
```

## The Test File

`apps/epistola/src/test/kotlin/app/epistola/suite/architecture/InlineStyleHygieneTest.kt`

The test walks source files with `Files.walk()`, skips `dist/`, `build/`, `coverage/`, and `test/` directories, and ignores comment lines. Each check reports actionable violation messages listing the file, line number, and offending code.
