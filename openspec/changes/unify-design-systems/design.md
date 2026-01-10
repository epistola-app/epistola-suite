# Design: Unified Design System

## Context

The Epistola Suite has two frontend rendering paths:
1. **Server-side (Thymeleaf + HTMX)**: Admin pages for managing tenants, templates, variants
2. **Client-side (React + Vite)**: Rich template editor embedded in Thymeleaf shell

Currently, the React editor uses shadcn/ui components styled with Tailwind CSS, while Thymeleaf pages use custom CSS in `main.css`. This creates visual inconsistency.

### Stakeholders
- End users: See inconsistent UI between editor and admin pages
- Developers: Maintain two separate styling systems

### Constraints
- Tailwind should NOT appear in Thymeleaf templates (too verbose, breaks SSR simplicity)
- React components should continue working with existing patterns
- Must not break the editor's isolated styling (`.template-editor-root`)

## Goals / Non-Goals

### Goals
- Single source of truth for design tokens (colors, spacing, radius)
- Semantic CSS classes usable by both React and Thymeleaf
- Visual consistency across all pages
- Easy to maintain and extend

### Non-Goals
- Full design system documentation site
- Dark mode support (Phase 2)
- Component library npm package
- Replacing shadcn/ui in the editor

## Decisions

### Decision 1: Shared CSS File with Semantic Classes

**What**: Create `design-system.css` with semantic component classes (`.btn`, `.input`, `.badge`, etc.) that compile Tailwind-like styling into plain CSS.

**Why**:
- Thymeleaf templates get clean, readable markup
- React components can use these classes directly
- No build step required for Thymeleaf pages
- Familiar pattern (Bootstrap-like API)

**Alternatives considered**:
1. **CSS-in-JS extraction**: Generate CSS from React components → Too complex, runtime overhead
2. **Tailwind in Thymeleaf**: Use `@apply` to create components → Still requires Tailwind build, verbose
3. **Duplicate styles**: Maintain two CSS files → Sync nightmare

### Decision 2: CSS Custom Properties for Tokens

**What**: Define colors, spacing, and radius as CSS custom properties in `:root`.

**Why**:
- Single source of truth
- Easy to override for theming (future)
- Works in both plain CSS and Tailwind

**Token structure**:
```css
:root {
  /* Colors */
  --color-primary: #1f2937;
  --color-primary-foreground: #fafafa;
  --color-destructive: #dc2626;
  /* ... */

  /* Spacing */
  --spacing-1: 0.25rem;
  --spacing-2: 0.5rem;
  /* ... */

  /* Radius */
  --radius: 0.375rem;
  --radius-sm: 0.25rem;
  --radius-lg: 0.5rem;
}
```

### Decision 3: Component Class Naming Convention

**What**: Use BEM-lite naming: `.btn`, `.btn-primary`, `.btn-sm`, `.input`, `.badge-success`.

**Why**:
- Familiar to most developers
- No special tooling required
- Easy to compose: `class="btn btn-primary btn-sm"`

**Pattern**:
- Base class: `.btn`, `.input`, `.badge`, `.card`
- Variant modifier: `.btn-primary`, `.btn-destructive`, `.badge-success`
- Size modifier: `.btn-sm`, `.btn-lg`, `.input-sm`
- State: Native (`:hover`, `:disabled`, `:focus-visible`)

### Decision 4: File Organization

**What**:
```
apps/epistola/src/main/resources/static/css/
├── design-system/
│   ├── tokens.css      # CSS custom properties
│   ├── button.css      # .btn classes
│   ├── input.css       # .input, .textarea classes
│   ├── badge.css       # .badge classes
│   ├── card.css        # .card classes
│   └── index.css       # @import all
└── main.css            # @import design-system + page-specific
```

**Why**:
- Modular and easy to navigate
- Each component is self-contained
- Can import into editor's CSS if needed

### Decision 5: React Component Integration

**What**: Update React components to optionally use semantic classes.

**Example** (button.tsx):
```tsx
// Current: Tailwind utilities via CVA
const buttonVariants = cva("inline-flex items-center...", {...})

// Updated: Semantic classes
const variantClasses = {
  default: "btn-primary",
  destructive: "btn-destructive",
  // ...
};

function Button({ variant, size, className, ...props }) {
  return (
    <button className={cn("btn", variantClasses[variant], sizeClasses[size], className)} {...props} />
  );
}
```

**Why**:
- Minimal change to existing components
- Can progressively migrate
- Semantic classes compose with custom `className`

## Risks / Trade-offs

| Risk | Impact | Mitigation |
|------|--------|------------|
| Visual regression in editor | High | Test editor thoroughly after CSS changes |
| CSS specificity conflicts | Medium | Use specific selectors, test both contexts |
| Increased CSS bundle size | Low | Minimal overhead, can purge unused |
| Maintenance of two class systems | Medium | Document clearly, lint for consistency |

## Migration Plan

1. **Phase 1**: Create design-system CSS with button (already PoC'd)
2. **Phase 2**: Add input, badge, card components
3. **Phase 3**: Update all Thymeleaf templates to use new classes
4. **Phase 4**: Optionally update React components to use semantic classes
5. **Phase 5**: Remove legacy `.btn-danger`, `.btn-small` aliases

**Rollback**: Each phase is independent. Can revert any component without affecting others.

## Open Questions

1. ~~Should we create a separate module (`modules/design-system`) or keep CSS in `apps/epistola`?~~
   **Decision**: Keep in `apps/epistola` for simplicity. Revisit if we add more apps.

2. Should React components be modified to use semantic classes, or keep using Tailwind internally?
   **Recommendation**: Optional migration. Both approaches work. Semantic classes reduce bundle size but Tailwind provides more flexibility for editor-specific styling.

3. How to handle the editor's CSS isolation (`.template-editor-root { all: initial }`)?
   **Recommendation**: Import shared tokens into editor's `index.css`. Component classes may or may not be needed inside the editor root.
