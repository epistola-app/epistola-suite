# Tasks: Unify Design Systems

## 1. Setup Design System Structure

- [x] 1.1 Create `css/design-system/` directory structure
- [x] 1.2 Create `tokens.css` with CSS custom properties (colors, spacing, radius)
- [x] 1.3 Create `index.css` that imports all component CSS files
- [x] 1.4 Update `main.css` to import design-system

## 2. Button Component

- [x] 2.1 Define `.btn` base styles
- [x] 2.2 Add variant classes (`.btn-primary`, `.btn-secondary`, `.btn-destructive`, `.btn-outline`, `.btn-ghost`)
- [x] 2.3 Add size classes (`.btn-sm`, `.btn-lg`, `.btn-icon`)
- [x] 2.4 Add disabled state styles
- [x] 2.5 Ensure `<a>` and `<button>` render identically
- [x] 2.6 Move button styles to `design-system/button.css`

## 3. Input Component

- [x] 3.1 Define `.input` base styles (border, padding, focus ring)
- [x] 3.2 Define `.textarea` styles
- [x] 3.3 Add error state (`.input-error` and `aria-invalid`)
- [x] 3.4 Add disabled state styles
- [x] 3.5 Add size variants (`.input-sm`, `.input-lg`)

## 4. Badge Component

- [x] 4.1 Define `.badge` base styles (inline-flex, pill shape)
- [x] 4.2 Add variant classes (`.badge-primary`, `.badge-secondary`, `.badge-destructive`, `.badge-outline`)
- [x] 4.3 Migrate existing `.badge-draft`, `.badge-published`, `.badge-archived` to new system

## 5. Card Component

- [x] 5.1 Define `.card` base styles (background, border/shadow, radius)
- [x] 5.2 Define `.card-header`, `.card-content`, `.card-footer` sections
- [x] 5.3 Apply to `.form-section` in Thymeleaf templates (replaced with `.card`)

## 6. Update Thymeleaf Templates

- [x] 6.1 Update `tenants/list.html` to use new component classes
- [x] 6.2 Update `templates/list.html` to use new component classes
- [x] 6.3 Update `templates/detail.html` to use new component classes
- [x] 6.4 Remove button showcase section from `detail.html`
- [x] 6.5 Update form inputs to use `.input` class

## 6b. Complete Design System Modularization

- [x] 6b.1 Create `base.css` with CSS reset and body defaults
- [x] 6b.2 Create `form.css` for form-group and form-error styles
- [x] 6b.3 Create `table.css` for table component with `.table` class
- [x] 6b.4 Create `tag.css` for tag component styles
- [x] 6b.5 Create `layout.css` for container, page-header, section-header,
       breadcrumb, actions, inline-form, search-box
- [x] 6b.6 Create `footer.css` for app-footer component
- [x] 6b.7 Create `utilities.css` for text utility classes
- [x] 6b.8 Reduce `main.css` to single design system import
- [x] 6b.9 Update templates to use `.container` instead of `.regular`
- [x] 6b.10 Update templates to use `.table` class on tables

## 7. React Component Integration

**Skipped** - React components stay with Tailwind. Design tokens are conceptually
shared; both systems use the same color values and spacing conventions.

## 8. Thymeleaf Layout System

Single source of truth for page structure - no more duplicating head/footer in every page.

- [x] 8.1 Create `fragments/head.html` with common `<head>` content (meta, css, htmx)
- [x] 8.2 Create `fragments/layout.html` as the main layout template
- [x] 8.3 Update `fragments/footer.html` if needed for layout integration (no changes needed)
- [x] 8.4 Refactor `tenants/list.html` to use layout
- [x] 8.5 Refactor `templates/list.html` to use layout
- [x] 8.6 Refactor `templates/detail.html` to use layout

## 9. Cleanup & Documentation

- [ ] 9.1 Remove legacy class aliases (`.btn-danger`, `.btn-small`) after migration
- [x] 9.2 Add inline documentation comments to CSS files
- [ ] 9.3 Visual regression test: compare editor and Thymeleaf buttons side-by-side

## Dependencies

- Tasks 2-5 can be done in parallel
- Task 6 depends on Tasks 2-5
- Task 7 depends on Tasks 1-5
- Task 8 depends on all previous tasks

## Validation

Each component task should be validated by:
1. Visual inspection in browser (both editor and Thymeleaf pages)
2. Testing all variants and states
3. Testing responsive behavior
4. Testing in both light context (Thymeleaf) and editor context
