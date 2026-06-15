# `data-testid` Reference

Canonical list of `data-testid` attributes in the Epistola Suite Thymeleaf templates.
These attributes are a **soft contract** for the external test suite (website-repo
Playwright / demo-reel scenarios). Removing or renaming one is a breaking change
for that consumer.

## Conventions

- Attribute name: `data-testid` (matches Playwright `getByTestId()` and demo-reel
  `strategy: "testId"`).
- Naming: `<area>-<role>` in kebab-case. A single generic name is reused when the
  same UI pattern appears on multiple pages and only one instance is ever on screen
  at a time (e.g. `create-form-submit`, `page-title`, `confirm-dialog-confirm`).
- Area-specific names are used only when the elements genuinely differ.

---

## Shared / Cross-cutting

| `data-testid`            | Attached to                                                                                               | Covers                                                                                                                                     |
| ------------------------ | --------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `confirm-dialog-confirm` | Destructive confirmation button, built dynamically by `window.openConfirmDialog` in `fragments/htmx.html` | Every delete flow in the app (tenant, theme, template, attribute, environment, API key, catalog, variant, font, stencil, asset, code-list) |
| `search-input`           | `<input name="q">` inside `fragments/search.html`                                                         | All pages that use the shared search fragment: themes, templates, environments, stencils, fonts, attributes, code-lists                    |
| `page-title`             | Page-header `<h1>`                                                                                        | Tenants list, tenant home, template detail, theme detail, generation-history, consumers                                                    |
| `alert-success`          | `.alert.alert-success` banner                                                                             | Login logout message, catalog saved confirmation, feature toggle saved confirmation, catalog browse success, feedback sync                 |

---

## Create Forms (dialogs)

Every "create new" form opens in a shared modal dialog loaded into `#dialog-host`
(see [`htmx.md`](htmx.md) → "Create Forms: Modal Dialogs"). Each form exposes the
same three anchors, with a per-entity prefix `<entity>` ∈ { `template`, `theme`,
`api-key`, `environment`, `stencil`, `attribute`, `font`, `asset`, `code-list`,
`load-test` }:

| `data-testid`                | Attached to                                                         | Covers                                                                |
| ---------------------------- | ------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `<entity>-create-open`       | "New …" trigger on `<entity>/list.html` (`hx-get` → `#dialog-host`) | All ten create forms                                                  |
| `<entity>-create-open-empty` | the same trigger inside the list's empty-state                      | The eight whose list can be empty (all except fonts/assets)           |
| `create-form-submit`         | Submit button inside each create dialog (`#create-<entity>-dialog`) | All create dialogs — generic, since only one dialog is open at a time |

The dialog element carries both `id="create-<entity>-dialog"` and a matching
`data-testid` (e.g. `create-template-dialog`), so a test opens via the trigger and
asserts/scopes on the dialog. Field inputs keep stable `id`s (`#name`, `#slug`, …),
scoped as `#create-<entity>-dialog #name`.

---

## Per-Area Anchors (Batch 3)

### Templates

| `data-testid`  | Attached to                                                              | Covers                                              |
| -------------- | ------------------------------------------------------------------------ | --------------------------------------------------- |
| `template-tab` | Tab-strip `<a>` on `templates/detail.html` (paired with `data-tab-name`) | Variants, Deployments, Data Contract, Settings tabs |
| `template-row` | `<tr>` in `templates/list.html` (paired with `data-template-slug`)       | Template list rows for search / delete assertions   |

### Template Variants

| `data-testid`           | Attached to                                                | Covers                               |
| ----------------------- | ---------------------------------------------------------- | ------------------------------------ |
| `variant-create-open`   | "+ New Variant" button on `templates/detail/variants.html` | Opening the create-variant dialog    |
| `variant-create-submit` | Submit button inside `#create-variant-dialog`              | Submitting the variant creation form |

### Themes

| `data-testid`         | Attached to                                       | Covers                             |
| --------------------- | ------------------------------------------------- | ---------------------------------- |
| `theme-default-badge` | `.badge.badge-primary` on `themes/list.html`      | Marking the tenant's default theme |
| `delete-action`       | Danger-zone delete button on `themes/detail.html` | Permanently deleting a theme       |

### Template Settings

| `data-testid`   | Attached to                                                   | Covers                          |
| --------------- | ------------------------------------------------------------- | ------------------------------- |
| `delete-action` | Danger-zone delete button on `templates/detail/settings.html` | Permanently deleting a template |

### Catalogs

| `data-testid`           | Attached to                                  | Covers                               |
| ----------------------- | -------------------------------------------- | ------------------------------------ |
| `catalog-create-open`   | "New Catalog" button on `catalogs/list.html` | Opening the create-catalog dialog    |
| `catalog-create-submit` | Submit button inside `#create-dialog`        | Submitting the catalog creation form |

### Feature Toggles

| `data-testid`           | Attached to                                                                          | Covers                     |
| ----------------------- | ------------------------------------------------------------------------------------ | -------------------------- |
| `feature-toggle`        | `.feature-toggle-item` container on `features.html` (paired with `data-feature-key`) | Each individual toggle row |
| `feature-toggle-switch` | `.toggle-switch-slider` inside the toggle item                                       | The visual switch element  |

### API Keys

| `data-testid`          | Attached to                                                                                        | Covers                                                |
| ---------------------- | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `api-key-row`          | `<tr>` in `api-keys/list.html` (paired with `data-api-key-name`)                                   | API key list rows                                     |
| `api-key-delete`       | Delete button on `api-keys/list.html`                                                              | Revoking an API key                                   |
| `api-key-created`      | The one-time-secret reveal swapped over the form (`createdReveal` fragment in `api-keys/new.html`) | Asserting the secret is revealed in the create dialog |
| `api-key-created-done` | "Done" link in the reveal footer (back to the list)                                                | Closing the reveal after copying the key              |
| `api-key-name`         | `<dd>` for the key name (reveal fragment, and the `api-keys/created.html` non-HTMX fallback page)  | Verifying the created key's name                      |

---

## Notes

- **Purely additive.** No behavioural changes; these attributes exist only to give
  external test suites stable anchors.
- **Removing one?** Call it out in PR review — it's a breaking change for the
  website-repo consumer.
- **Missing one?** Follow the convention above and update this file.
