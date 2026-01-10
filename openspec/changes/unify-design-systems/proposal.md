# Change: Unify Design Systems

## Why

The application currently has two separate styling approaches:
1. **Editor (React)**: Uses shadcn/ui components with Tailwind CSS utility classes
2. **Thymeleaf pages**: Uses custom CSS classes in `main.css`

This causes visual inconsistency between the template editor and admin pages (tenants, templates lists, etc.). Buttons, inputs, badges, and other UI elements look different across the application.

## What Changes

- Create a shared CSS design system with semantic component classes (`.btn`, `.btn-primary`, `.input`, `.badge`, etc.)
- Extract design tokens (colors, spacing, radius) into CSS custom properties
- Update React components to use semantic classes alongside/instead of Tailwind utilities
- Update Thymeleaf templates to use the same semantic classes
- Ensure visual consistency across editor and admin pages

**Key principle**: No Tailwind classes in Thymeleaf templates. Both React and Thymeleaf use the same semantic CSS classes.

## Impact

- Affected specs: `design-system` (new capability)
- Affected code:
  - `apps/epistola/src/main/resources/static/css/main.css` - Button PoC already added
  - `modules/editor/src/main/typescript/index.css` - Design tokens
  - `modules/editor/src/main/typescript/components/ui/*.tsx` - React components
  - `apps/epistola/src/main/resources/templates/**/*.html` - Thymeleaf templates

## Scope

### Phase 1: Core Components (This Proposal)
- Button (variants: default, secondary, destructive, outline, ghost; sizes: sm, default, lg, icon)
- Input (text, textarea)
- Badge (variants matching button)
- Card (container with shadow/border)

### Phase 2: Extended Components (Future)
- Select/Dropdown
- Table styling
- Form layouts
- Dialog/Modal
- Navigation components

## Success Criteria

1. A button in Thymeleaf (`<button class="btn btn-primary">`) looks identical to a Button in React
2. Design tokens (colors, radius, spacing) are defined once and used everywhere
3. No visual regression in the editor
4. Thymeleaf pages have a modern, consistent look matching the editor aesthetic
