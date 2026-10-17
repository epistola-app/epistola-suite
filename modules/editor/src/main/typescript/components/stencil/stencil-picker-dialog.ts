// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil picker dialog — modal for browsing and picking a stencil to insert.
 *
 * A template uses a stencil by reference, so there is nothing to choose but
 * the stencil: its working copy is what the instance shows.
 *
 * Flow:
 * 1. Search/browse stencils → select one → Insert (or double-click)
 * 2. When the stencil declares parameters, bind them → Insert
 *
 * Or "Create New": a new, empty stencil to author in place.
 *
 * Uses native <dialog> element, same pattern as asset-picker-dialog.ts.
 */

import type { StencilCallbacks, StencilRef, StencilSummary, StencilWorkingCopy } from './types.js';
import type { FieldPath } from '../../engine/schema-paths.js';
import type { FetchedStencils } from './instances.js';
import { renderBindingRow } from './binding-row.js';
import { missingRequiredParameters } from './parameter-requirements.js';
import { stencilIdentity } from './node-types.js';

const HTML_ENTITIES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** Escape author-controlled text for HTML element-content interpolation. */
function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => HTML_ENTITIES[c] ?? c);
}

/** A stencil ready to insert: its working copy and every stencil it uses. */
export interface PreparedStencil {
  copy: StencilWorkingCopy;
  fetched: FetchedStencils;
}

/** Fetches what inserting a stencil needs, or says why it cannot go here. */
export type PrepareStencilFn = (
  ref: StencilRef,
) => Promise<{ ok: true; prepared: PreparedStencil } | { ok: false; message: string }>;

export type StencilPickerResult =
  | { action: 'create-new'; ref: StencilRef }
  | {
      action: 'use-existing';
      prepared: PreparedStencil;
      /**
       * Map of parameter name → JSONata expression. Empty when the stencil
       * declares no parameters or the user accepted the defaults.
       */
      bindings: Record<string, string>;
      /** Namespace alias for the inserted stencil's parameters. Defaults to `'params'`. */
      paramsAlias: string;
    }
  | null;

export interface StencilPickerOptions {
  /**
   * Fetches the picked stencil (and the stencils it uses) and checks it may
   * go where it is being inserted. A refusal is shown in the dialog.
   */
  prepare: PrepareStencilFn;
  /**
   * Stencils ({@link stencilIdentity}) the insertion point is already the
   * content of: inserting one would make it contain itself. Their cards are
   * disabled, saying why.
   */
  disabledStencils?: Set<string>;
  /**
   * Variables visible at the insertion point (template data fields,
   * iteration scope, system parameters). Used to populate the
   * field-picker dropdowns and the advanced expression dialog when the
   * user is binding parameters.
   */
  fieldPaths?: FieldPath[];
  /** Example data for live preview inside the advanced expression dialog. */
  getExampleData?: () => Record<string, unknown> | undefined;
  /** BCP-47 locale passed through to the advanced expression dialog. */
  locale?: string;
}

export async function openStencilPickerDialog(
  callbacks: StencilCallbacks,
  options: StencilPickerOptions,
): Promise<StencilPickerResult> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';
    dialog.dataset.testid = 'stencil-picker';

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
          <div id="stencil-picker-error" class="callout callout--error" data-testid="stencil-picker-error" style="display: none; margin: 0 var(--ep-space-6);"></div>
          <div class="stencil-picker-list" id="stencil-list">
            <div class="stencil-picker-loading">Loading stencils...</div>
          </div>
        </div>

        <!-- Create new stencil form (hidden initially) -->
        <div id="stencil-step-create" style="display: none;">
          <div style="padding: var(--ep-space-3) var(--ep-space-6);">
            <button type="button" id="stencil-back-create" class="stencil-picker-btn" style="margin-bottom: var(--ep-space-2);">&larr; Back to stencils</button>
            <div style="font-weight: 500; font-size: var(--ep-text-sm); margin-bottom: var(--ep-space-3);">Create New Stencil</div>
            <div style="margin-bottom: var(--ep-space-2);">
              <label style="font-size: var(--ep-text-xs); font-weight: 500; display: block; margin-bottom: var(--ep-space-1);">Name</label>
              <input type="text" id="create-stencil-name" class="ep-input" style="width: 100%;" maxlength="100" placeholder="Corporate Header" />
            </div>
            <div style="margin-bottom: var(--ep-space-2);">
              <label style="font-size: var(--ep-text-xs); font-weight: 500; display: block; margin-bottom: var(--ep-space-1);">Key</label>
              <input type="text" id="create-stencil-slug" class="ep-input" style="width: 100%;" maxlength="50" placeholder="corporate-header" />
              <div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-top: 2px;">Lowercase letters, numbers, and hyphens only</div>
            </div>
            <div id="create-stencil-error" style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626); display: none;"></div>
          </div>
        </div>

        <!-- Step 2: Parameter binding (hidden initially) -->
        <div id="stencil-step-bindings" style="display: none;">
          <div style="padding: var(--ep-space-3) var(--ep-space-6);">
            <button type="button" id="stencil-back-bindings" class="stencil-picker-btn" style="margin-bottom: var(--ep-space-2);">&larr; Back to stencils</button>
            <div id="stencil-binding-title" style="font-weight: 500; font-size: var(--ep-text-sm); margin-bottom: var(--ep-space-2);"></div>
            <div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom: var(--ep-space-3);">Bind each parameter to a JSONata expression. Leave optional ones blank to use the default.</div>
            <div id="stencil-binding-rows"></div>
          </div>
        </div>

        <div class="stencil-picker-footer">
          ${callbacks.createStencil ? '<button type="button" class="stencil-picker-btn create-new">Create New</button>' : ''}
          <div style="flex: 1;"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert" data-testid="stencil-picker-insert" disabled>Insert</button>
          <button type="button" class="stencil-picker-btn insert create-confirm" style="display: none;" disabled>Create</button>
        </div>
      </div>
    `;

    const stepList = dialog.querySelector<HTMLElement>('#stencil-step-list')!;
    const stepCreate = dialog.querySelector<HTMLElement>('#stencil-step-create')!;
    const stepBindings = dialog.querySelector<HTMLElement>('#stencil-step-bindings')!;
    const list = dialog.querySelector<HTMLElement>('#stencil-list')!;
    const pickError = dialog.querySelector<HTMLElement>('#stencil-picker-error')!;
    const bindingRows = dialog.querySelector<HTMLElement>('#stencil-binding-rows')!;
    const bindingTitle = dialog.querySelector<HTMLElement>('#stencil-binding-title')!;
    const searchInput = dialog.querySelector<HTMLInputElement>('#stencil-search')!;
    const closeBtn = dialog.querySelector<HTMLElement>('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!;
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!;
    const createNewBtn = dialog.querySelector<HTMLElement>('.create-new');
    const createConfirmBtn = dialog.querySelector<HTMLButtonElement>('.create-confirm')!;
    const backCreateBtn = dialog.querySelector<HTMLElement>('#stencil-back-create')!;
    const backBindingsBtn = dialog.querySelector<HTMLElement>('#stencil-back-bindings')!;
    const createNameInput = dialog.querySelector<HTMLInputElement>('#create-stencil-name')!;
    const createSlugInput = dialog.querySelector<HTMLInputElement>('#create-stencil-slug')!;
    const createError = dialog.querySelector<HTMLElement>('#create-stencil-error')!;

    let selectedStencil: StencilSummary | null = null;
    let pendingPrepared: PreparedStencil | null = null;
    let bindingValues: Record<string, string> = {};
    let debounceTimer: ReturnType<typeof setTimeout> | null = null;

    const close = (result: StencilPickerResult) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    const refOf = (s: StencilSummary): StencilRef => ({
      stencilKey: s.stencilKey,
      catalogKey: s.catalogKey,
    });

    function showPickError(message: string | null) {
      pickError.textContent = message ?? '';
      pickError.style.display = message ? '' : 'none';
    }

    // ── Step 1: Stencil list ──

    function renderStencilList(stencils: StencilSummary[]) {
      if (stencils.length === 0) {
        list.innerHTML = callbacks.createStencil
          ? '<div class="stencil-picker-empty">No stencils found. Create a new one.</div>'
          : '<div class="stencil-picker-empty">No stencils found.</div>';
        return;
      }

      list.innerHTML = '';
      for (const stencil of stencils) {
        const card = document.createElement('div');
        card.className = 'stencil-picker-card';
        card.dataset.stencilKey = stencil.stencilKey;
        card.dataset.testid = 'stencil-picker-card';

        const isRecursive = options.disabledStencils?.has(stencilIdentity(refOf(stencil))) ?? false;
        if (isRecursive) {
          card.classList.add('stencil-picker-card--disabled');
          card.setAttribute('aria-disabled', 'true');
          card.title = 'Cannot insert here: this is the content of this stencil already';
        }

        const tags =
          stencil.tags.length > 0
            ? stencil.tags
                .map((t) => `<span class="stencil-picker-tag">${escapeHtml(t)}</span>`)
                .join('')
            : '';
        const catalogBadge = stencil.catalogKey
          ? `<span class="stencil-picker-tag">${escapeHtml(stencil.catalogKey)}</span>`
          : '';
        const recursionBadge = isRecursive
          ? '<span class="stencil-picker-tag" style="background:var(--ep-amber-50,#fffbeb);color:var(--ep-amber-700,#b45309);">would contain itself</span>'
          : '';

        card.innerHTML = `
          <div class="stencil-picker-card-name">${escapeHtml(stencil.name)} ${catalogBadge}${recursionBadge}</div>
          ${tags ? `<div class="stencil-picker-card-meta"><span class="stencil-picker-card-tags">${tags}</span></div>` : ''}
          ${stencil.description ? `<div class="stencil-picker-card-desc">${escapeHtml(stencil.description)}</div>` : ''}
        `;

        if (!isRecursive) {
          card.addEventListener('click', () => {
            list
              .querySelectorAll('.stencil-picker-card')
              .forEach((c) => c.classList.remove('selected'));
            card.classList.add('selected');
            selectedStencil = stencil;
            showPickError(null);
            insertBtn.disabled = false;
          });
          card.addEventListener('dblclick', () => {
            selectedStencil = stencil;
            void doInsert();
          });
        }

        list.appendChild(card);
      }
    }

    function showStencilList() {
      stepCreate.style.display = 'none';
      stepBindings.style.display = 'none';
      stepList.style.display = '';
      insertBtn.style.display = '';
      insertBtn.textContent = 'Insert';
      insertBtn.disabled = selectedStencil === null;
      createConfirmBtn.style.display = 'none';
      if (createNewBtn) createNewBtn.style.display = '';
      pendingPrepared = null;
      bindingValues = {};
    }

    // ── Step 2: Parameter binding ──

    function showBindingStep(prepared: PreparedStencil) {
      pendingPrepared = prepared;
      bindingValues = {};
      const schema = prepared.copy.parameterSchema;
      const props = schema?.properties ?? {};
      const required = new Set(schema?.required ?? []);

      stepList.style.display = 'none';
      stepCreate.style.display = 'none';
      stepBindings.style.display = '';
      if (createNewBtn) createNewBtn.style.display = 'none';

      bindingTitle.textContent = `${prepared.copy.name} parameters`;
      bindingRows.innerHTML = '';

      const fieldPaths = options.fieldPaths ?? [];
      for (const [name, prop] of Object.entries(props)) {
        const row = renderBindingRow({
          name,
          prop,
          required: required.has(name),
          initialValue: '',
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

      insertBtn.style.display = '';
      insertBtn.textContent = 'Insert';
      updateBindingInsertState();
    }

    function updateBindingInsertState() {
      if (!pendingPrepared) {
        insertBtn.disabled = true;
        return;
      }
      const schema = pendingPrepared.copy.parameterSchema;
      insertBtn.disabled =
        schema !== undefined && missingRequiredParameters(schema, bindingValues).length > 0;
    }

    // ── Create new stencil ──

    function showCreateForm() {
      stepList.style.display = 'none';
      stepBindings.style.display = 'none';
      stepCreate.style.display = '';
      insertBtn.style.display = 'none';
      if (createNewBtn) createNewBtn.style.display = 'none';
      createConfirmBtn.style.display = '';
      createNameInput.value = '';
      createSlugInput.value = '';
      createError.style.display = 'none';
      updateCreateButtonState();
      createNameInput.focus();
    }

    let slugManuallyEdited = false;

    createNameInput.addEventListener('input', () => {
      if (!slugManuallyEdited) createSlugInput.value = nameToSlug(createNameInput.value);
      updateCreateButtonState();
    });

    createSlugInput.addEventListener('input', () => {
      slugManuallyEdited = createSlugInput.value !== nameToSlug(createNameInput.value);
      updateCreateButtonState();
    });

    function updateCreateButtonState() {
      createConfirmBtn.disabled = !createNameInput.value.trim() || !createSlugInput.value.trim();
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
      const key = createSlugInput.value.trim();
      if (!name || !key) return;

      createError.style.display = 'none';
      createConfirmBtn.disabled = true;
      createConfirmBtn.textContent = 'Creating...';
      try {
        const ref = await callbacks.createStencil(key, name);
        close({ action: 'create-new', ref });
      } catch (e) {
        createError.textContent = (e as Error).message || 'Failed to create stencil';
        createError.style.display = '';
        createConfirmBtn.disabled = false;
        createConfirmBtn.textContent = 'Create';
      }
    }

    // ── Insert ──

    async function doInsert() {
      if (!selectedStencil) return;
      insertBtn.disabled = true;
      insertBtn.textContent = 'Loading...';
      showPickError(null);

      const outcome = await options.prepare(refOf(selectedStencil)).catch((e: unknown) => ({
        ok: false as const,
        message: e instanceof Error ? e.message : 'Failed to load the stencil',
      }));
      insertBtn.textContent = 'Insert';
      if (!outcome.ok) {
        showPickError(outcome.message);
        insertBtn.disabled = false;
        return;
      }

      // A stencil with parameters goes through the binding step; otherwise
      // insert immediately with empty bindings and the default alias.
      const props = outcome.prepared.copy.parameterSchema?.properties;
      if (props && Object.keys(props).length > 0) {
        showBindingStep(outcome.prepared);
        return;
      }
      close({
        action: 'use-existing',
        prepared: outcome.prepared,
        bindings: {},
        paramsAlias: 'params',
      });
    }

    // ── Event handlers ──

    callbacks
      .searchStencils('')
      .then((stencils) => renderStencilList(stencils))
      .catch(() => {
        list.innerHTML = '<div class="stencil-picker-empty">Failed to load stencils.</div>';
      });

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
    insertBtn.addEventListener('click', () => {
      // On the binding step, "Insert" closes with the collected bindings.
      if (pendingPrepared && stepBindings.style.display !== 'none') {
        close({
          action: 'use-existing',
          prepared: pendingPrepared,
          bindings: { ...bindingValues },
          paramsAlias: 'params',
        });
        return;
      }
      void doInsert();
    });
    createNewBtn?.addEventListener('click', () => showCreateForm());
    createConfirmBtn.addEventListener('click', () => void doCreate());
    backCreateBtn.addEventListener('click', () => showStencilList());
    backBindingsBtn.addEventListener('click', () => showStencilList());

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
