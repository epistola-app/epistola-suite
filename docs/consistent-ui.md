# Consistent UI Strategy

This document outlines the design token system used to achieve visual consistency across the React-based editor and server-rendered Thymeleaf pages.

## Implementation Status

**IMPLEMENTED** - The design token system is now active in `main.css`.

---

## Approach: CSS Custom Properties (Design Tokens)

We implemented a design token system using pure CSS custom properties, matching the editor's token values without requiring Tailwind or additional build steps.

### Why This Approach

1. **No build step required** - Pure CSS, just edit and reload
2. **Radix accessibility preserved** - Editor keeps its battle-tested accessibility
3. **Visual consistency** - Shared tokens ensure matching colors, spacing, and typography
4. **Low maintenance** - Single source of truth for design values
5. **Browser support** - OKLch and relative color syntax work in modern browsers (Chrome 111+, Firefox 113+, Safari 15.4+)

---

## Design Token System

### File Location

```
apps/epistola/src/main/resources/static/css/main.css
```

### Token Categories

#### Colors (OKLch for perceptual uniformity)

```css
:root {
  --background: oklch(1 0 0);           /* Pure white */
  --foreground: oklch(0.145 0 0);       /* Near black text */
  --card: oklch(1 0 0);
  --card-foreground: oklch(0.145 0 0);
  --primary: oklch(0.205 0 0);          /* Dark gray for buttons */
  --primary-foreground: oklch(0.985 0 0);
  --secondary: oklch(0.97 0 0);         /* Light gray background */
  --secondary-foreground: oklch(0.205 0 0);
  --muted: oklch(0.97 0 0);
  --muted-foreground: oklch(0.556 0 0); /* Medium gray for labels */
  --accent: oklch(0.97 0 0);
  --accent-foreground: oklch(0.205 0 0);
  --destructive: oklch(0.577 0.245 27.325);  /* Red for danger */
  --success: oklch(0.556 0.224 142.478);     /* Green for success */
  --info: oklch(0.546 0.192 252.892);        /* Blue for info */
  --border: oklch(0.922 0 0);           /* Light gray borders */
  --input: oklch(0.922 0 0);
  --ring: oklch(0.708 0 0);             /* Focus ring color */
}
```

#### Spacing

```css
:root {
  --space-1: 0.25rem;   /* 4px */
  --space-2: 0.5rem;    /* 8px */
  --space-3: 0.75rem;   /* 12px */
  --space-4: 1rem;      /* 16px */
  --space-5: 1.25rem;   /* 20px */
  --space-6: 1.5rem;    /* 24px */
  --space-8: 2rem;      /* 32px */
}
```

#### Border Radius

```css
:root {
  --radius: 0.625rem;   /* 10px - base */
  --radius-sm: calc(var(--radius) - 4px);  /* 6px */
  --radius-md: calc(var(--radius) - 2px);  /* 8px */
  --radius-lg: var(--radius);              /* 10px */
  --radius-xl: calc(var(--radius) + 4px);  /* 14px */
}
```

#### Shadows

```css
:root {
  --shadow-sm: 0 1px 2px oklch(0 0 0 / 5%);
  --shadow-md: 0 4px 6px oklch(0 0 0 / 10%);
  --shadow-lg: 0 10px 15px oklch(0 0 0 / 10%);
}
```

#### Typography

```css
:root {
  --font-sans: "Inter", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  --font-mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
}
```

---

## Usage Examples

### Buttons

```css
.btn {
  background-color: var(--primary);
  color: var(--primary-foreground);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-4);
}

.btn:hover {
  filter: brightness(0.85);
}

.btn-danger {
  background-color: var(--destructive);
}

.btn-secondary {
  background-color: var(--muted);
  color: var(--muted-foreground);
}
```

### Form Inputs

```css
.form-group input {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-3);
  background-color: var(--background);
}

.form-group input:focus {
  border-color: var(--ring);
  box-shadow: 0 0 0 2px oklch(from var(--ring) l c h / 20%);
}
```

### Cards

```css
.form-section,
.metric-card {
  background-color: var(--background);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}
```

### Badges (using relative color syntax)

```css
.badge-draft {
  background-color: oklch(from var(--info) l c h / 10%);
  color: var(--info);
  border: 1px solid oklch(from var(--info) l c h / 30%);
}

.badge-published {
  background-color: oklch(from var(--success) l c h / 10%);
  color: var(--success);
  border: 1px solid oklch(from var(--success) l c h / 30%);
}
```

---

## Migration from Previous Colors

| Old Value | New Token | Purpose |
|-----------|-----------|---------|
| `#4a90d9` (blue) | `var(--primary)` (dark gray) | Primary buttons, links |
| `#357abd` | `filter: brightness(0.85)` | Hover states |
| `#dc3545` | `var(--destructive)` | Danger buttons, errors |
| `#6c757d` | `var(--muted-foreground)` | Secondary text |
| `#333` | `var(--foreground)` | Main text |
| `#f5f5f5` | `var(--secondary)` | Page background |
| `#fff` | `var(--background)` | Cards, inputs |
| `#ced4da`, `#e9ecef` | `var(--border)` | Borders |
| `4px` | `var(--radius-sm)` | Small border radius |
| `8px` | `var(--radius-md)` | Medium border radius |

**Note:** The primary color changed from blue to dark gray to match the editor's design. This creates a more neutral, professional look.

---

## Browser Compatibility

### Required Features

1. **CSS Custom Properties** - Supported in all modern browsers
2. **OKLch colors** - Chrome 111+, Firefox 113+, Safari 15.4+
3. **Relative color syntax** (`oklch(from var(...) l c h / x%)`) - Chrome 119+, Firefox 128+, Safari 16.4+

Given the project targets modern browsers (2026), this is acceptable.

---

## Verification

### Visual Comparison

1. Run the application: `./gradlew :apps:epistola:bootRun`
2. Open both editor and admin pages
3. Compare visual elements:
   - Buttons (primary, secondary, danger)
   - Form inputs and focus states
   - Cards and shadows
   - Badges (draft, published, archived)
   - Typography

### Functional Testing

```bash
./gradlew test
```

### Browser Testing

Test in:
- Chrome (primary)
- Firefox
- Safari

---

## Future Considerations

### If Deeper Consistency Needed

If behavioral consistency becomes important (matching Radix components exactly), consider:

1. **Web Components (Lit)** - Create framework-agnostic components
2. **Shared Tailwind Preset** - Add Tailwind to Thymeleaf pages
3. **Component Library** - Extract shared patterns to a module

### Dark Mode

The token system supports dark mode by redefining variables:

```css
@media (prefers-color-scheme: dark) {
  :root {
    --background: oklch(0.145 0 0);
    --foreground: oklch(0.985 0 0);
    /* ... */
  }
}
```

This is not implemented yet but the architecture supports it.

---

## Files Reference

| File | Purpose |
|------|---------|
| `apps/epistola/src/main/resources/static/css/main.css` | Design tokens + all styles |
| `modules/editor/src/main/typescript/index.css` | Editor's token definitions (reference) |
| `docs/consistent-ui.md` | This documentation |
