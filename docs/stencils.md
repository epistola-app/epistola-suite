# Stencils — Reusable Template Components

Stencils are versioned, reusable components that can be inserted into any template. They allow you to maintain common elements (headers, footers, address blocks) in one place and use them across multiple templates.

## Concepts

### Stencil
A stencil is a named, reusable component with metadata (name, description, tags) and versioned content. Stencils are scoped to a tenant.

### Stencil Version
Each stencil has one or more versions following the same lifecycle as template versions:

- **Draft** — editable content, not yet available for insertion
- **Published** — frozen content, available for insertion into templates
- **Archived** — read-only, no longer available for new insertions

At most one draft version exists per stencil at a time.

### Stencil Instance
When a stencil is inserted into a template, a **copy** of the stencil version's content is placed in the template. The stencil node tracks which stencil and version it came from (`stencilId` and `version` in props), but the content itself is a regular part of the template document — fully editable and independently savable.

## Working with stencils

### Creating a stencil

**From the management page:**
1. Go to Stencils in the navigation
2. Click "New Stencil"
3. Enter an ID (slug), name, optional description and tags
4. The stencil is created with no versions — add content from the template editor

**From the template editor:**
1. Add a "Stencil" block from the palette
2. Choose "Create New (Empty)"
3. Add content to the stencil container
4. In the inspector, click "Publish as Stencil" — enter name and slug
5. The content is published as version 1

### Inserting an existing stencil

1. In the template editor, add a "Stencil" block from the palette (click or drag)
2. Choose "Use Existing" in the picker dialog
3. Search or browse available stencils
4. Select a stencil, then pick a version (published or draft)
5. The version's content is copied into the template with fresh IDs

### Editing stencil content

Stencil instances in a template have three states:

| State | Block header shows | Content | Actions |
|-------|-------------------|---------|---------|
| **Locked** | `Stencil: header v1` | Read-only (dimmed) | Start Editing, Detach |
| **Editing draft** | `Stencil: header — editing draft` | Fully editable | Save to Draft, Publish Draft, Discard, Detach |
| **Unlinked** | `Stencil` | Fully editable | Publish as Stencil |

**To edit a published stencil's content:**
1. Click the stencil node to select it
2. In the inspector, click "Start Editing" — this creates a draft on the backend and unlocks the content
3. Edit the content as needed
4. Click "Save to Draft" to persist changes
5. Click "Publish Draft" to make the new version available to other templates
6. The stencil locks again on the new published version

### Upgrading stencils

When a newer version of a stencil is published, templates using an older version see an upgrade indicator.

**From the template editor:**
- The block header shows `⬆ v3` when a newer version exists
- Click the stencil, then "Upgrade to v3" in the inspector
- Content is replaced with the new version's content

**From the stencil management page:**
- The stencil detail page shows a "Templates using this stencil" table
- Select rows using checkboxes (or "Select all")
- Choose a target version from the dropdown
- Click "Apply to selected" to upgrade all selected template drafts

Bulk upgrade only modifies **drafts** — published versions in environments are never changed. You must separately publish and deploy the updated drafts.

### Detaching

Detaching removes the stencil reference (stencilId, version) from the node but keeps the content. The stencil block becomes a regular container. This is useful when you want to customize the content independently.

### Nesting

Stencils cannot be nested — you cannot place a stencil inside another stencil. This is enforced both in the editor (the stencil component's child denylist) and at publish time (the server validates no nested stencil nodes).

## Architecture

### Data model

Stencils are stored in two tables:
- `stencils` — metadata (id, name, description, tags, timestamps)
- `stencil_versions` — versioned content (version number, status, content as JSONB, timestamps)

Both use composite primary keys with tenant isolation (`tenant_key`).

### In the template document

A stencil instance is a node with `type: "stencil"` and a `children` slot. The stencil's content lives as real nodes in the parent document's flat node/slot graph — not as a sub-document. This means:

- Single editor engine, unified undo/redo
- Styles and variables cascade from parent to stencil content
- DnD works normally within the stencil
- PDF rendering treats stencils as containers

Props on the stencil node:
- `stencilId` — slug of the source stencil (null if unlinked)
- `version` — version number the content was copied from (null if unlinked)
- `isDraft` — whether the user is in draft editing mode

### Editor integration

The stencil component uses the `renderCanvas` hook for the locked state (pointer-events: none overlay) and the `getLabel` hook for dynamic block header labels. The `StencilInspector` Lit component provides contextual actions based on the stencil state.

All backend communication uses the `StencilCallbacks` interface — the editor never calls endpoints directly. The hosting app wires callback implementations in the editor mount options.

### Content replacement

When a stencil is inserted or upgraded, the content is **re-keyed** — every node and slot ID is replaced with a fresh unique ID. This prevents collisions when the same stencil content is inserted multiple times.

The re-keying excludes the source document's root node and slot — only the root's children and their descendants are copied into the stencil's slot.

### Server-side upgrade

The `UpdateStencilInTemplate` command upgrades all instances of a stencil within a template variant's draft. It uses `StencilContentReplacer` which performs the same re-key and replace operation as the editor, but in Kotlin on the server.

### Known limitations

- **Concurrent edits**: If two users upgrade different stencils in the same template draft simultaneously, the last write wins. This is a rare scenario — bulk upgrades are sequential, and editing a draft while a bulk upgrade runs is unlikely.
- **No preview before upgrade**: The bulk upgrade applies immediately to drafts. A side-by-side before/after preview is planned for a future release.
- **No catalogs**: Stencils are browsed directly. Named collections (catalogs) for organizing stencils are a future feature.

## API endpoints

All stencil endpoints are under `/tenants/{tenantId}/stencils` and use content negotiation — HTMX requests get Thymeleaf fragments, non-HTMX requests get JSON.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | List stencils |
| GET | `/search` | Search stencils (HTMX fragment or JSON) |
| POST | `/` | Create stencil (form or JSON with optional content + publish) |
| GET | `/{id}` | Stencil detail |
| PATCH | `/{id}` | Update metadata |
| DELETE | `/{id}/delete` | Delete stencil |
| PUT | `/{id}/draft` | Save content to draft |
| GET | `/{id}/usage` | Usage details (which templates use this stencil) |
| POST | `/{id}/upgrade` | Upgrade stencil in a specific template draft |
| GET | `/{id}/versions` | List versions |
| POST | `/{id}/versions` | Create version (idempotent) |
| GET | `/{id}/versions/{v}` | Get version content |
| POST | `/{id}/versions/{v}/publish` | Publish version |
| POST | `/{id}/versions/{v}/archive` | Archive version |

## Test coverage

- **42 editor engine tests** — insertion, re-keying, nesting, undo/redo, multiple instances
- **21 editor action tests** — callback flows, props transitions, getLabel, error handling
- **6 StencilContentReplacer unit tests** — server-side content replacement
- **12 backend integration tests** — CRUD, version lifecycle, publish validation, tenant isolation, upgrade
- **Total: 81 stencil-specific tests**
