/**
 * Stencil picker dialog — modal for browsing, selecting stencils, and choosing a version.
 *
 * Two-step flow:
 * 1. Search/browse stencils → select one
 * 2. Pick a version (published, draft, archived) → insert
 *
 * Uses native <dialog> element, same pattern as asset-picker-dialog.ts.
 */

import type {
  StencilCallbacks,
  StencilRef,
  StencilSummary,
  StencilVersionSummary,
  StencilVersionInfo,
} from './types.js';
import type { FieldPath } from '../../engine/schema-paths.js';
import { renderBindingRow } from './binding-row.js';

export type StencilPickerResult =
  | { action: 'create-new'; ref: StencilRef; version: number }
  | {
      action: 'use-existing';
      versionInfo: StencilVersionInfo;
      /**
       * Map of parameter name → JSONata expression. Empty when the picked
       * version declares no parameters or the user accepted the defaults.
       */
      bindings: Record<string, string>;
      /**
       * Namespace alias for the inserted stencil's parameters. Defaults to
       * `'params'`. Configurable per insertion to avoid shadowing when nested
       * inside another parametrised node.
       */
      paramsAlias: string;
    }
  | null;

export interface StencilPickerOptions {
  /**
   * Stencil IDs that would cause recursion if inserted at the cursor's
   * target slot. Cards for these stencils are rendered disabled with a
   * tooltip; users cannot select or insert them.
   */
  disabledStencilIds?: Set<string>;
  /**
   * Variables visible at the insertion point (template data fields,
   * iteration scope, system parameters). Used to populate the
   * field-picker dropdowns and the advanced expression dialog when the
   * user is binding parameters in step 4.
   */
  fieldPaths?: FieldPath[];
  /**
   * Example data for live preview inside the advanced expression dialog.
   * Same callback shape the inspector uses.
   */
  getExampleData?: () => Record<string, unknown> | undefined;
  /**
   * BCP-47 locale passed through to the advanced expression dialog so its
   * preview and number-format examples use the session's culture.
   */
  locale?: string;
  /**
   * When false (default), the binding step is skipped even if the picked
   * stencil version declares parameters — the stencil is inserted with
   * empty bindings. Existing data still renders; only authoring is gated.
   */
  stencilParametersEnabled?: boolean;
}

export async function openStencilPickerDialog(
  callbacks: StencilCallbacks,
  options: StencilPickerOptions = {},
): Promise<StencilPickerResult> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';

    dialog.innerHTML = `
      <div class="stencil-picker-content">
        <div class="stencil-picker-header">
          <h3>Add Stencil</h3>
          <button type="button" class="stencil-picker-close" aria-label="Close">&times;</button>
        </div>

        <!-- Step 1: Stencil list -->
        <div id="stencil-step-list">
          <div class="stencil-picker-search">
            <input type="text" id="stencil-search" class="ep-input" placeholder="Search stencils..." />
          </div>
          <div class="stencil-picker-list" id="stencil-list">
            <div class="stencil-picker-loading">Loading stencils...</div>
          </div>
        </div>

        <!-- Step 2: Create new stencil form (hidden initially) -->
        <div id="stencil-step-create" data-hidden="true">
          <div class="stencil-picker-section">
            <button type="button" id="stencil-back-create" class="stencil-picker-btn stencil-picker-group">&larr; Back to stencils</button>
            <div class="stencil-picker-subtitle-mb3">Create New Stencil</div>
            <div class="stencil-picker-group">
              <label class="stencil-picker-label">Name</label>
              <input type="text" id="create-stencil-name" class="ep-input stencil-picker-full" placeholder="Corporate Header" />
            </div>
            <div class="stencil-picker-group">
              <label class="stencil-picker-label">ID (slug)</label>
              <input type="text" id="create-stencil-slug" class="ep-input stencil-picker-full" placeholder="corporate-header" />
              <div class="stencil-picker-hint">Lowercase letters, numbers, and hyphens only</div>
            </div>
            <div id="create-stencil-error" class="stencil-picker-error" data-hidden="true"></div>
          </div>
        </div>

        <!-- Step 3: Version picker (hidden initially) -->
        <div id="stencil-step-versions" data-hidden="true">
          <div class="stencil-picker-section">
            <button type="button" id="stencil-back" class="stencil-picker-btn stencil-picker-group">&larr; Back to stencils</button>
            <div id="stencil-version-title" class="stencil-picker-subtitle"></div>
          </div>
          <div class="stencil-picker-list" id="stencil-version-list">
            <div class="stencil-picker-loading">Loading versions...</div>
          </div>
        </div>

        <!-- Step 4: Parameter binding (hidden initially) -->
        <div id="stencil-step-bindings" data-hidden="true">
          <div class="stencil-picker-section">
            <button type="button" id="stencil-back-bindings" class="stencil-picker-btn stencil-picker-group">&larr; Back to versions</button>
            <div id="stencil-binding-title" class="stencil-picker-subtitle-mb2"></div>
            <div class="stencil-picker-muted stencil-picker-group-mb3">Bind each parameter to a JSONata expression. Leave optional ones blank to use the default.</div>
            <div id="stencil-binding-rows"></div>
          </div>
        </div>

        <div class="stencil-picker-footer">
          <button type="button" class="stencil-picker-btn create-new">Create New</button>
          <div class="stencil-picker-flex-fill"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert" disabled>Insert</button>
          <button type="button" class="stencil-picker-btn insert create-confirm" data-hidden="true" disabled>Create</button>
        </div>
      </div>
    `;

    const stepList = dialog.querySelector<HTMLElement>('#stencil-step-list')!;
    const stepCreate = dialog.querySelector<HTMLElement>('#stencil-step-create')!;
    const stepVersions = dialog.querySelector<HTMLElement>('#stencil-step-versions')!;
    const stepBindings = dialog.querySelector<HTMLElement>('#stencil-step-bindings')!;
    const list = dialog.querySelector<HTMLElement>('#stencil-list')!;
    const versionList = dialog.querySelector<HTMLElement>('#stencil-version-list')!;
    const versionTitle = dialog.querySelector<HTMLElement>('#stencil-version-title')!;
    const bindingRows = dialog.querySelector<HTMLElement>('#stencil-binding-rows')!;
    const bindingTitle = dialog.querySelector<HTMLElement>('#stencil-binding-title')!;
    const searchInput = dialog.querySelector<HTMLInputElement>('#stencil-search')!;
    const closeBtn = dialog.querySelector<HTMLElement>('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!;
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!;
    const createNewBtn = dialog.querySelector<HTMLElement>('.create-new')!;
    const backBtn = dialog.querySelector<HTMLElement>('#stencil-back')!;
    const backCreateBtn = dialog.querySelector<HTMLElement>('#stencil-back-create')!;
    const backBindingsBtn = dialog.querySelector<HTMLElement>('#stencil-back-bindings')!;
    const createNameInput = dialog.querySelector<HTMLInputElement>('#create-stencil-name')!;
    const createSlugInput = dialog.querySelector<HTMLInputElement>('#create-stencil-slug')!;
    const createError = dialog.querySelector<HTMLElement>('#create-stencil-error')!;

    let selectedStencil: StencilSummary | null = null;
    let selectedVersion: StencilVersionSummary | null = null;
    let debounceTimer: ReturnType<typeof setTimeout> | null = null;

    const close = (result: StencilPickerResult) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    // ── Step 1: Stencil list ──

    function renderStencilList(stencils: StencilSummary[]) {
      if (stencils.length === 0) {
        list.innerHTML =
          '<div class="stencil-picker-empty">No stencils found. Create a new one.</div>';
        return;
      }

      list.innerHTML = '';
      for (const stencil of stencils) {
        const card = document.createElement('div');
        card.className = 'stencil-picker-card';
        card.dataset.stencilId = stencil.id;

        const isRecursive = options.disabledStencilIds?.has(stencil.id) ?? false;
        if (isRecursive) {
          card.dataset.disabled = 'true';
          card.title = 'Cannot insert: this stencil already appears in the ancestor chain';
        }

        const tags =
          stencil.tags.length > 0
            ? stencil.tags.map((t) => `<span class="stencil-picker-tag">${t}</span>`).join('')
            : '';
        const versionLabel = stencil.latestPublishedVersion
          ? `v${stencil.latestPublishedVersion}`
          : stencil.latestVersion
            ? `v${stencil.latestVersion} (draft)`
            : '<span class="stencil-picker-tag--muted">no versions</span>';

        const catalogBadge = stencil.catalogKey
          ? `<span class="stencil-picker-tag">${stencil.catalogKey}</span>`
          : '';

        const recursionBadge = isRecursive
          ? '<span class="stencil-picker-tag stencil-picker-tag--warning">would recurse</span>'
          : '';

        card.innerHTML = `
          <div class="stencil-picker-card-name">${stencil.name} ${catalogBadge}${recursionBadge}</div>
          <div class="stencil-picker-card-meta">
            <span class="stencil-picker-card-version">${versionLabel}</span>
            ${tags ? `<span class="stencil-picker-card-tags">${tags}</span>` : ''}
          </div>
          ${stencil.description ? `<div class="stencil-picker-card-desc">${stencil.description}</div>` : ''}
        `;

        if (!isRecursive) {
          card.addEventListener('click', () => {
            selectedStencil = stencil;
            showVersionPicker(stencil);
          });
        }

        list.appendChild(card);
      }
    }

    // ── Step 2: Version picker ──

    async function showVersionPicker(stencil: StencilSummary) {
      stepList.dataset.hidden = 'true';
      stepBindings.dataset.hidden = 'true';
      stepVersions.dataset.hidden = 'false';
      versionTitle.textContent = `Versions for "${stencil.name}"`;
      versionList.innerHTML = '<div class="stencil-picker-loading">Loading versions...</div>';
      insertBtn.disabled = true;
      insertBtn.textContent = 'Insert';
      selectedVersion = null;
      pendingBindingVersionInfo = null;

      try {
        const versions = await callbacks.listVersions({
          stencilId: stencil.id,
          catalogKey: stencil.catalogKey ?? 'default',
        });
        renderVersionList(versions);
      } catch {
        versionList.innerHTML = '<div class="stencil-picker-empty">Failed to load versions.</div>';
      }
    }

    function renderVersionList(versions: StencilVersionSummary[]) {
      if (versions.length === 0) {
        versionList.innerHTML = '<div class="stencil-picker-empty">No versions available.</div>';
        return;
      }

      // Sort: draft first, then published (newest first), then archived
      const sorted = [...versions].sort((a, b) => {
        const order: Record<string, number> = { draft: 0, published: 1, archived: 2 };
        if (order[a.status] !== order[b.status]) return order[a.status] - order[b.status];
        return b.version - a.version;
      });

      versionList.innerHTML = '';
      for (const version of sorted) {
        const card = document.createElement('div');
        card.className = 'stencil-picker-card';

        const statusBadge =
          version.status === 'draft'
            ? '<span class="stencil-picker-status stencil-picker-status--draft">draft</span>'
            : version.status === 'published'
              ? '<span class="stencil-picker-status stencil-picker-status--published">published</span>'
              : '<span class="stencil-picker-status stencil-picker-status--archived">archived</span>';

        card.innerHTML = `
          <div class="stencil-picker-card-name">Version ${version.version} ${statusBadge}</div>
          <div class="stencil-picker-card-meta">
            <span>Created: ${new Date(version.createdAt).toLocaleDateString()}</span>
            ${version.publishedAt ? `<span>Published: ${new Date(version.publishedAt).toLocaleDateString()}</span>` : ''}
          </div>
        `;

        card.addEventListener('click', () => {
          versionList
            .querySelectorAll<HTMLElement>('.stencil-picker-card')
            .forEach((c) => (c.dataset.selected = 'false'));
          card.dataset.selected = 'true';
          selectedVersion = version;
          insertBtn.disabled = false;
        });

        card.addEventListener('dblclick', () => {
          selectedVersion = version;
          doInsert();
        });

        versionList.appendChild(card);
      }
    }

    function showStencilList() {
      stepVersions.dataset.hidden = 'true';
      stepCreate.dataset.hidden = 'true';
      stepBindings.dataset.hidden = 'true';
      stepList.dataset.hidden = 'false';
      insertBtn.disabled = true;
      insertBtn.dataset.hidden = 'false';
      createNewBtn.dataset.hidden = 'false';
      selectedVersion = null;
    }

    // ── Step 4: Parameter binding ──

    let pendingBindingVersionInfo: StencilVersionInfo | null = null;
    let bindingValues: Record<string, string> = {};

    function showBindingStep(versionInfo: StencilVersionInfo) {
      pendingBindingVersionInfo = versionInfo;
      bindingValues = {};
      const props = versionInfo.parameterSchema?.properties ?? {};
      const required = new Set(versionInfo.parameterSchema?.required ?? []);

      stepList.dataset.hidden = 'true';
      stepCreate.dataset.hidden = 'true';
      stepVersions.dataset.hidden = 'true';
      stepBindings.dataset.hidden = 'false';
      createNewBtn.dataset.hidden = 'true';

      bindingTitle.textContent = `${versionInfo.stencilName} v${versionInfo.version} parameters`;
      bindingRows.innerHTML = '';

      const fieldPaths = options.fieldPaths ?? [];

      for (const [name, prop] of Object.entries(props)) {
        const row = renderBindingRow({
          name,
          prop,
          required: required.has(name),
          initialValue: bindingValues[name] ?? '',
          fieldPaths,
          getExampleData: options.getExampleData,
          locale: options.locale,
          onChange: (newValue) => {
            if (newValue) bindingValues[name] = newValue;
            else delete bindingValues[name];
            updateBindingInsertState();
          },
          paramDatasetKey: name,
        });
        bindingRows.appendChild(row.element);
      }

      insertBtn.dataset.hidden = 'false';
      insertBtn.textContent = 'Insert';
      updateBindingInsertState();
    }

    function updateBindingInsertState() {
      if (!pendingBindingVersionInfo) {
        insertBtn.disabled = true;
        return;
      }
      const required = pendingBindingVersionInfo.parameterSchema?.required ?? [];
      const allRequiredBound = required.every(
        (name) => (bindingValues[name] ?? '').trim().length > 0,
      );
      insertBtn.disabled = !allRequiredBound;
    }

    // ── Create new stencil ──

    function showCreateForm() {
      stepList.dataset.hidden = 'true';
      stepVersions.dataset.hidden = 'true';
      stepCreate.dataset.hidden = 'false';
      insertBtn.dataset.hidden = 'true';
      createNewBtn.dataset.hidden = 'true';
      createNameInput.value = '';
      createSlugInput.value = '';
      createError.dataset.hidden = 'true';
      createNameInput.focus();
    }

    let slugManuallyEdited = false;

    createNameInput.addEventListener('input', () => {
      if (!slugManuallyEdited) {
        createSlugInput.value = nameToSlug(createNameInput.value);
      }
      updateCreateButtonState();
    });

    createSlugInput.addEventListener('input', () => {
      slugManuallyEdited = createSlugInput.value !== nameToSlug(createNameInput.value);
      updateCreateButtonState();
    });

    function updateCreateButtonState() {
      const createBtn = dialog.querySelector<HTMLButtonElement>(
        '.stencil-picker-btn.create-confirm',
      );
      if (createBtn) {
        createBtn.disabled = !createNameInput.value.trim() || !createSlugInput.value.trim();
      }
    }

    function nameToSlug(name: string): string {
      return name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-|-$/g, '');
    }

    async function doCreate() {
      if (!callbacks.createStencil) return;
      const name = createNameInput.value.trim();
      const slug = createSlugInput.value.trim();
      if (!name || !slug) return;

      createError.dataset.hidden = 'true';
      const createBtn = dialog.querySelector<HTMLButtonElement>(
        '.stencil-picker-btn.create-confirm',
      );
      if (createBtn) {
        createBtn.disabled = true;
        createBtn.textContent = 'Creating...';
      }

      try {
        const result = await callbacks.createStencil(slug, name);
        close({
          action: 'create-new',
          ref: result.ref,
          version: result.version,
        });
      } catch (e) {
        createError.textContent = (e as Error).message || 'Failed to create stencil';
        createError.dataset.hidden = 'false';
        if (createBtn) {
          createBtn.disabled = false;
          createBtn.textContent = 'Create';
        }
      }
    }

    // ── Insert ──

    async function doInsert() {
      if (!selectedStencil || !selectedVersion) return;
      insertBtn.disabled = true;
      insertBtn.textContent = 'Loading...';

      const ref: StencilRef = {
        stencilId: selectedStencil.id,
        catalogKey: selectedStencil.catalogKey ?? 'default',
      };
      const versionInfo = await callbacks.getStencilVersion(ref, selectedVersion.version);
      if (!versionInfo) {
        insertBtn.textContent = 'Insert';
        insertBtn.disabled = false;
        return;
      }

      // If the stencil has parameters declared and the feature is enabled,
      // transition to the binding step. Otherwise insert immediately with
      // empty bindings + default alias.
      const props = versionInfo.parameterSchema?.properties;
      const hasParams = !!(props && Object.keys(props).length > 0);
      if (hasParams && options.stencilParametersEnabled) {
        showBindingStep(versionInfo);
        return;
      }
      close({ action: 'use-existing', versionInfo, bindings: {}, paramsAlias: 'params' });
    }

    // ── Event handlers ──

    // Initial load
    callbacks
      .searchStencils('')
      .then((stencils) => renderStencilList(stencils))
      .catch(() => {
        list.innerHTML = '<div class="stencil-picker-empty">Failed to load stencils.</div>';
      });

    // Search with debounce
    searchInput.addEventListener('input', () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        callbacks
          .searchStencils(searchInput.value.trim())
          .then((stencils) => renderStencilList(stencils))
          .catch(() => {
            list.innerHTML = '<div class="stencil-picker-empty">Search failed.</div>';
          });
      }, 300);
    });

    closeBtn.addEventListener('click', () => close(null));
    cancelBtn.addEventListener('click', () => close(null));
    insertBtn.addEventListener('click', async () => {
      // On the binding step, "Insert" closes with the collected bindings.
      // Elsewhere it kicks off the version-fetch + (conditional) binding flow.
      if (pendingBindingVersionInfo && stepBindings.dataset.hidden !== 'true') {
        close({
          action: 'use-existing',
          versionInfo: pendingBindingVersionInfo,
          bindings: { ...bindingValues },
          paramsAlias: 'params',
        });
        return;
      }
      doInsert();
    });
    createNewBtn.addEventListener('click', () => {
      if (callbacks.createStencil) {
        showCreateForm();
        // Show the create-confirm button, hide insert
        const createConfirmBtn = dialog.querySelector<HTMLElement>('.create-confirm');
        if (createConfirmBtn) createConfirmBtn.dataset.hidden = 'false';
      } else {
        // No create callback — insert empty (legacy fallback)
        close({ action: 'create-new', ref: { stencilId: '', catalogKey: '' }, version: 0 });
      }
    });
    const createConfirmBtn = dialog.querySelector<HTMLButtonElement>('.create-confirm');
    createConfirmBtn?.addEventListener('click', () => doCreate());
    backBtn.addEventListener('click', () => showStencilList());
    backCreateBtn.addEventListener('click', () => showStencilList());
    backBindingsBtn.addEventListener('click', () => {
      pendingBindingVersionInfo = null;
      bindingValues = {};
      if (selectedStencil) {
        showVersionPicker(selectedStencil);
      } else {
        showStencilList();
      }
    });

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close(null);
      }
    });
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close(null);
    });

    document.body.appendChild(dialog);
    dialog.showModal();
    searchInput.focus();
  });
}
