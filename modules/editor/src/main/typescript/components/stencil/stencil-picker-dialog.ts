/**
 * Stencil picker dialog — modal for browsing and selecting stencils.
 *
 * Uses native <dialog> element, same pattern as asset-picker-dialog.ts.
 * Returns a Promise that resolves with the selected stencil version info or null.
 */

import type { StencilCallbacks, StencilSummary, StencilVersionInfo } from './types.js';

export type StencilPickerResult =
  | { action: 'create-new' }
  | { action: 'use-existing'; versionInfo: StencilVersionInfo }
  | null;

export function openStencilPickerDialog(callbacks: StencilCallbacks): Promise<StencilPickerResult> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';

    dialog.innerHTML = `
      <div class="stencil-picker-content">
        <div class="stencil-picker-header">
          <h3>Add Stencil</h3>
          <button type="button" class="stencil-picker-close" aria-label="Close">&times;</button>
        </div>

        <div class="stencil-picker-search">
          <input type="text" id="stencil-search" class="ep-input" placeholder="Search stencils..." />
        </div>

        <div class="stencil-picker-list" id="stencil-list">
          <div class="stencil-picker-loading">Loading stencils...</div>
        </div>

        <div class="stencil-picker-footer">
          <button type="button" class="stencil-picker-btn create-new">Create New (Empty)</button>
          <div style="flex: 1;"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert" disabled>Insert</button>
        </div>
      </div>
    `;

    const list = dialog.querySelector<HTMLElement>('#stencil-list')!;
    const searchInput = dialog.querySelector<HTMLInputElement>('#stencil-search')!;
    const closeBtn = dialog.querySelector<HTMLElement>('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!;
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!;
    const createNewBtn = dialog.querySelector<HTMLElement>('.create-new')!;

    let selectedStencil: StencilSummary | null = null;
    let debounceTimer: ReturnType<typeof setTimeout> | null = null;

    const close = (result: StencilPickerResult) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    function renderList(stencils: StencilSummary[]) {
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

        const tags = stencil.tags.length > 0
          ? stencil.tags.map((t) => `<span class="stencil-picker-tag">${t}</span>`).join('')
          : '';
        const versionLabel = stencil.latestPublishedVersion
          ? `v${stencil.latestPublishedVersion}`
          : '<span class="text-muted">no published version</span>';

        card.innerHTML = `
          <div class="stencil-picker-card-name">${stencil.name}</div>
          <div class="stencil-picker-card-meta">
            <span class="stencil-picker-card-version">${versionLabel}</span>
            ${tags ? `<span class="stencil-picker-card-tags">${tags}</span>` : ''}
          </div>
          ${stencil.description ? `<div class="stencil-picker-card-desc">${stencil.description}</div>` : ''}
        `;

        const isInsertable = stencil.latestPublishedVersion != null;

        card.addEventListener('click', () => {
          list.querySelectorAll('.stencil-picker-card').forEach((c) => c.classList.remove('selected'));
          card.classList.add('selected');
          selectedStencil = stencil;
          insertBtn.disabled = !isInsertable;
        });

        if (isInsertable) {
          card.addEventListener('dblclick', () => {
            selectedStencil = stencil;
            doInsert();
          });
        }

        list.appendChild(card);
      }
    }

    async function doInsert() {
      if (!selectedStencil || !selectedStencil.latestPublishedVersion) return;
      insertBtn.disabled = true;
      insertBtn.textContent = 'Loading...';

      const versionInfo = await callbacks.getStencilVersion(
        selectedStencil.id,
        selectedStencil.latestPublishedVersion,
      );
      if (!versionInfo) {
        insertBtn.textContent = 'Insert';
        insertBtn.disabled = false;
        return;
      }
      close({ action: 'use-existing', versionInfo });
    }

    // Initial load
    callbacks
      .searchStencils('')
      .then((stencils) => renderList(stencils))
      .catch(() => {
        list.innerHTML = '<div class="stencil-picker-empty">Failed to load stencils.</div>';
      });

    // Search with debounce
    searchInput.addEventListener('input', () => {
      if (debounceTimer) clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        const query = searchInput.value.trim();
        callbacks
          .searchStencils(query)
          .then((stencils) => {
            selectedStencil = null;
            insertBtn.disabled = true;
            renderList(stencils);
          })
          .catch(() => {
            list.innerHTML = '<div class="stencil-picker-empty">Search failed.</div>';
          });
      }, 300);
    });

    // Button actions
    closeBtn.addEventListener('click', () => close(null));
    cancelBtn.addEventListener('click', () => close(null));
    insertBtn.addEventListener('click', () => doInsert());
    createNewBtn.addEventListener('click', () => close({ action: 'create-new' }));

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
