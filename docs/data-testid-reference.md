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

## Create Forms (Batch 2)

| `data-testid`        | Attached to                              | Covers                                                                                           |
| -------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `create-form-submit` | Submit button inside `.create-form-card` | Theme create, template create, font upload, environment create, attribute create, API key create |

---

## Per-Area Anchors (Batch 3)

### Templates

| `data-testid`  | Attached to                                                              | Covers                                              |
| -------------- | ------------------------------------------------------------------------ | --------------------------------------------------- |
| `template-tab` | Tab-strip `<a>` on `templates/detail.html` (paired with `data-tab-name`) | Variants, Deployments, Data Contract, Settings tabs |
| `template-row` | `<tr>` in `templates/list.html` (paired with `data-template-slug`)       | Template list rows for search / delete assertions   |

### Template Variants

| `data-testid`           | Attached to                                                | Covers                                        |
| ----------------------- | ---------------------------------------------------------- | --------------------------------------------- |
| `variant-create-open`   | "+ New Variant" button on `templates/detail/variants.html` | Opening the create-variant dialog             |
| `variant-create-submit` | Submit button inside `#create-variant-dialog`              | Submitting the variant creation form          |
| `variant-ready`         | "Ready" badge on a variant card                            | A variant whose working model is marked ready |
| `variant-not-ready`     | "Not ready" badge on a variant card                        | A variant with unmarked changes               |
| `variant-mark-ready`    | "Mark ready" button on a variant card                      | Marking one variant ready                     |
| `template-mark-ready`   | "Mark all variants ready" button above the variant grid    | Marking every variant of the template ready   |

### Stencils

| `data-testid`                         | Attached to                                   | Covers                                    |
| ------------------------------------- | --------------------------------------------- | ----------------------------------------- |
| `stencil-ready` / `stencil-not-ready` | Readiness badge on `stencils/detail.html`     | Whether the working copy is marked ready  |
| `stencil-mark-ready`                  | "Mark ready" button on `stencils/detail.html` | Marking the stencil ready                 |
| `stencil-reads-template-data`         | Checkbox on `stencils/detail.html`            | Whether the stencil reads template data   |
| `stencil-usage-row`                   | `<tr>` of the usage list                      | A variant using the stencil, with its fit |

### Template editor: stencils

Rendered by the editor bundle (`modules/editor`), not by a Thymeleaf template.

| `data-testid`                 | Attached to                                                | Covers                                        |
| ----------------------------- | ---------------------------------------------------------- | --------------------------------------------- |
| `stencil-picker`              | The stencil picker `<dialog>`                              | Inserting a stencil                           |
| `stencil-picker-card`         | A stencil in the picker list (`data-stencil-key` names it) | Choosing the stencil                          |
| `stencil-picker-insert`       | The picker's Insert button                                 | Inserting the chosen stencil                  |
| `stencil-picker-error`        | The picker's refusal callout                               | Why a stencil cannot go here (e.g. recursion) |
| `stencil-start-editing`       | "Edit Stencil" in the stencil inspector                    | Editing the stencil in place                  |
| `stencil-read-only`           | Why "Edit Stencil" is withheld (read-only catalog)         | The withheld edit control                     |
| `stencil-missing`             | Warning that the instance's stencil no longer exists       | A dangling instance                           |
| `stencil-define-parameters`   | "Define parameters…" while editing the stencil             | The stencil's parameter schema                |
| `stencil-save`                | "Save Stencil"                                             | Saving the working copy                       |
| `stencil-save-and-mark-ready` | "Save and Mark Ready"                                      | Saving and marking the stencil ready          |
| `stencil-stop-editing`        | "Stop Editing"                                             | Back to the saved working copy                |
| `stencil-detach`              | "Detach from Stencil"                                      | Turning the instance into plain content       |
| `stencil-unlinked`            | Note on a stencil block that names no stencil              | An unlinked stencil block                     |
| `stencil-message`             | The inspector's result callout                             | Outcome of the last stencil action            |

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

| `data-testid`                                                        | Attached to                                                                   | Covers                                       |
| -------------------------------------------------------------------- | ----------------------------------------------------------------------------- | -------------------------------------------- |
| `catalog-create-open`                                                | "New Catalog" button on `catalogs/list.html`                                  | Opening the create-catalog dialog            |
| `catalog-create-submit`                                              | Submit button inside `#create-dialog`                                         | Submitting the catalog creation form         |
| `catalog-next-release`                                               | "Next release" card on `catalogs/browse.html` (`catalogs/next-release.html`)  | The draft, its changes and dependencies      |
| `catalog-draft-version`                                              | The draft's version in that card                                              | The number the next release gets             |
| `catalog-renumber-open`                                              | "Renumber" button                                                             | Opening the renumber dialog                  |
| `catalog-mark-ready`                                                 | "Mark all ready" button                                                       | Marking every changed resource ready         |
| `catalog-release-open`                                               | "Release" button (only while nothing blocks it)                               | Opening the release dialog                   |
| `catalog-release-blocked`                                            | Alert saying why Release is withheld                                          | Unready resources / unresolved dependencies  |
| `catalog-change-row`                                                 | `<tr>` per changed resource (paired with `data-resource`)                     | A resource's state and readiness             |
| `catalog-dependencies-edit` / `catalog-dependencies-resolve`         | Dependency actions                                                            | Editing declarations / resolving locks       |
| `catalog-dependency-row`                                             | `<tr>` per declared dependency                                                | A declaration and its lock                   |
| `release-submit`                                                     | Submit button of the release dialog                                           | Present only when the release can go through |
| `catalog-draft-new`                                                  | "New draft" button in the Next release card                                   | Opening the create-draft dialog              |
| `catalog-draft-discard`                                              | "Discard draft" button (confirmed)                                            | Discarding the draft the page works in       |
| `catalog-draft-bar`                                                  | Draft bar (`catalogs/drafts.html`); on content pages only with several drafts | Which draft a page works in                  |
| `catalog-draft-chosen` / `catalog-draft-base`                        | The chosen draft's number / its base release in the bar                       | The draft and what it started from           |
| `catalog-draft-picker` / `catalog-draft-link`                        | Links to the catalog's drafts (paired with `data-version`)                    | Switching to another draft                   |
| `catalog-draft-behind` / `catalog-draft-behind-list`                 | Badge / alert naming the releases the draft is behind, with their changes     | A draft that lacks later releases            |
| `catalog-draft-behind-release`                                       | `<li>` per release the draft is behind (paired with `data-version`)           | One release and what it changed              |
| `catalog-draft-none` / `catalog-draft-open`                          | Empty state of a catalog without a draft / its "Open draft" button            | Opening a draft from the latest release      |
| `catalog-draft-open-withheld` / `catalog-draft-controls-withheld`    | Why opening or managing drafts is not offered                                 | Missing role / release without content       |
| `create-draft-from` / `create-draft-submit` / `create-draft-error`   | Source select, submit and field error of the create-draft dialog              | Opening a draft from a draft or release      |
| `release-acknowledge-behind` / `release-acknowledge-behind-checkbox` | Release dialog's confirmation, one checkbox per release the draft is behind   | Releasing a draft that is behind             |
| `editor-draft`                                                       | Badge in the template editor's nav naming the draft (several drafts only)     | Which draft the editor saves into            |

### Deployments

| `data-testid`                                     | Attached to                                                                                        | Covers                                                                                                |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `environment-deployments` / `catalog-deployments` | The "Serves" card on `environments/detail.html` / the "Deployments" card on `catalogs/browse.html` | Every release served; a row per release carries `data-catalog` / `data-version`                       |
| `deploy-refusal`                                  | Alert of `environments/deploy-refusal.html`, on either page                                        | A deploy refused because the environment does not serve a release it renders with                     |
| `deploy-refusal-missing`                          | List inside that alert; an `<li data-reason="…">` per missing release                              | Each missing release and why (`NOT_DEPLOYED`, `NOT_INSTALLED`, `UNAVAILABLE`, `CONTENT_NOT_RETAINED`) |
| `deploy-with-dependencies`                        | "Deploy with its dependencies" form                                                                | Present only when every missing release is `NOT_DEPLOYED`                                             |
| `deploy-with-dependencies-withheld`               | Sentence saying why that offer is withheld                                                         | A missing release the tenant does not have, or has without content                                    |

### Feature Toggles

| `data-testid`           | Attached to                                                                          | Covers                     |
| ----------------------- | ------------------------------------------------------------------------------------ | -------------------------- |
| `feature-toggle`        | `.feature-toggle-item` container on `features.html` (paired with `data-feature-key`) | Each individual toggle row |
| `feature-toggle-switch` | `.ep-toggle-slider` inside the toggle item                                           | The visual switch element  |

### API Keys

| `data-testid`    | Attached to                                                      | Covers                           |
| ---------------- | ---------------------------------------------------------------- | -------------------------------- |
| `api-key-row`    | `<tr>` in `api-keys/list.html` (paired with `data-api-key-name`) | API key list rows                |
| `api-key-delete` | Delete button on `api-keys/list.html`                            | Revoking an API key              |
| `api-key-name`   | `<dd>` for the key name on `api-keys/created.html`               | Verifying the created key's name |

---

## Notes

- **Purely additive.** No behavioural changes; these attributes exist only to give
  external test suites stable anchors.
- **Removing one?** Call it out in PR review — it's a breaking change for the
  website-repo consumer.
- **Missing one?** Follow the convention above and update this file.
