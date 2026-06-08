# Inline Style Hygiene

The project enforces a strict no-inline-styles policy across all surfaces: Thymeleaf templates, TypeScript components, and CSS files. An automated test (`InlineStyleHygieneTest`) runs in CI and **fails the build** on any violation.

For the app UI, the goal is not to replace inline styles with a second utility-class framework. Prefer contextual, component-scoped, or page-scoped class names that describe what the element is. Use `data-*` attributes for state and CSS custom properties only as carriers for computed values. Do not put app UI CSS in template-local `<style>` elements; keep it in the app stylesheet unless there is a deliberate documented exception.

## Quick Reference

| What                        | Allowed Pattern                                    | Banned Pattern                                        |
| --------------------------- | -------------------------------------------------- | ----------------------------------------------------- |
| Static presentation         | Contextual CSS class                               | `style="color: red"`                                  |
| App template CSS placement  | Shared app stylesheet                              | Template-local `<style>` block                        |
| State-driven styling        | Data attribute + CSS `&[data-*]`                   | `classList.add('active')`                             |
| Computed values (pixel)     | CSS custom property + `calc()`                     | `style="--ep-depth: ${d}"` only for data carrier      |
| Hidden elements             | `data-hidden="true"` + CSS `&[data-hidden='true']` | `classList.add('hidden')` or `style.display = 'none'` |
| Design-system tokens        | Use existing token directly                        | `var(--ep-fake-75)` or `var(--ep-gray-50, #f9fafb)`   |
| Conditional Thymeleaf class | `th:classappend="${active ? ' active' : _}"`       | `style="..."` or duplicate `class` attrs              |
| Conditional Thymeleaf style | `th:style="'--ep-foo: ' + ${val}"`                 | `th:style="val ? 'display:block' : 'display:none'"`   |

## The Seven Checks

### 1. No `style="..."` in HTML templates

All inline `style` attributes in Thymeleaf templates must be replaced with CSS classes.

In `apps/epistola`, prefer classes that describe the page or component role over generic app-wide utility classes.

Do not add template-local `<style>` blocks for app pages or fragments. Put those rules in `apps/epistola/src/main/resources/static/css/main.css` so styling stays discoverable and reusable.

```html
<!-- BAD -->
<div style="margin-top: 12px; font-weight: 600;">Title</div>

<!-- GOOD -->
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

### 3. Design-system tokens must exist and be used consistently

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

The design system has a **discrete** scale, not a continuous one. There is no `gray-75` between `gray-50` and `gray-100`. There is no `6px` space token if `space-1` (4px) and `space-1-5` (6px) don't exist — pick the one that matches. The same rule applies to colors, spacing, fonts, radii, shadows, and z-indices. If the token you want doesn't exist, you are either picking a wrong value or you need to add a new token to `tokens.css`.

**What this check covers:** This rule is enforced by the test's no-fallback regex (`var(--ep-*, <something>)`) but the intent is broader — it's about token consistency. The test catches the fallback case; human review catches the made-up-token case.

### 4. No `th:style` without `--ep-*`

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

Reasoning: CSS files style elements; HTML elements carry class names and data for CSS to act upon. Keep the visibility rule in CSS and let the markup only declare the state.

### 5. No `var(--ep-*, <fallback>)` in TypeScript string literals

The same no-fallback rule applies to JavaScript string literals:

```typescript
// BAD
const color = valid ? "var(--ep-green-600, #16a34a)" : "var(--ep-destructive, #dc2626)";

// GOOD — use a CSS class or data attribute
element.dataset.validity = valid ? "valid" : "invalid";
```

### 6. No runtime `style.color` / `style.background` / `style.borderColor`

```typescript
// BAD
validityEl.style.color = "var(--ep-destructive, #dc2626)";

// GOOD — data attribute + CSS
validityEl.dataset.validity = "invalid";
```

**Exemption:** Content-driven colors from ProseMirror marks (e.g., `el.style.color = color` where `color` comes from `mark.attrs?.color`) are allowed because the color comes from the document model, not from a hardcoded value.

### 7. No `classList.add/remove/toggle/contains`

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

## Correct Patterns

### Static presentation -> contextual classes

Prefer names that describe the element's role in the page or component:

```html
<!-- BAD -->
<form class="ep-inline">

<!-- GOOD -->
<form class="nav-logout-form"></form>
```

```css
.nav-logout-form {
  display: inline;
}
```

Avoid rebuilding a generic app utility layer in `apps/epistola/src/main/resources/static/css/main.css`. If a style is specific to one page, fragment, or component, name it that way.

### App template CSS placement

For the app UI, keep CSS in the shared stylesheet instead of embedding `<style>` elements in Thymeleaf templates.

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

CSS follows the nesting pattern — data attributes go inside the base class:

```css
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
```

Do **not** write standalone top-level selectors:

```css
/* BAD — not nested */
.stencil-picker-card[data-selected="true"] {
}

/* BAD — not nested */
[data-selected="true"] {
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

Always use `data-hidden="true"` + CSS `&[data-hidden='true'] { display: none; }`. Nest it inside the element's base class when the selector belongs to a component stylesheet; in app-level page CSS, a scoped selector such as `.version-comparison-loading[data-hidden='true']` is also acceptable:

```typescript
// Template — initially hidden
html`<div id="create-error" class="stencil-picker-error" data-hidden="true"></div>`;

// JS — toggle visibility
errorEl.dataset.hidden = "true"; // hide
errorEl.dataset.hidden = "false"; // show
```

```css
.stencil-picker-error {
  &[data-hidden="true"] {
    display: none;
  }
}
```

```css
.version-comparison-loading[data-hidden="true"] {
  display: none;
}
```

## Running the Test

```bash
# Run all 7 checks
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*"

# Run a single check
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*classList*"
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*CSS*"
./gradlew :apps:epistola:test --tests "*InlineStyleHygieneTest*TypeScript*"
```

## The Test File

`apps/epistola/src/test/kotlin/app/epistola/suite/architecture/InlineStyleHygieneTest.kt`

The test walks source files with `Files.walk()`, skips `dist/`, `build/`, `coverage/`, and `test/` directories, and ignores comment lines. Each check reports actionable violation messages listing the file, line number, and offending code.
