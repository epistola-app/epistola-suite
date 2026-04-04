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
  StencilSummary,
  StencilVersionSummary,
  StencilVersionInfo,
} from './types.js';

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

        <!-- Step 1: Stencil list -->
        <div id="stencil-step-list">
          <div class="stencil-picker-search">
            <input type="text" id="stencil-search" class="ep-input" placeholder="Search stencils..." />
          </div>
          <div class="stencil-picker-list" id="stencil-list">
            <div class="stencil-picker-loading">Loading stencils...</div>
          </div>
        </div>

        <!-- Step 2: Version picker (hidden initially) -->
        <div id="stencil-step-versions" style="display: none;">
          <div style="padding: var(--ep-space-3) var(--ep-space-6);">
            <button type="button" id="stencil-back" class="stencil-picker-btn" style="margin-bottom: var(--ep-space-2);">&larr; Back to stencils</button>
            <div id="stencil-version-title" style="font-weight: 500; font-size: var(--ep-text-sm);"></div>
          </div>
          <div class="stencil-picker-list" id="stencil-version-list">
            <div class="stencil-picker-loading">Loading versions...</div>
          </div>
        </div>

        <div class="stencil-picker-footer">
          <button type="button" class="stencil-picker-btn create-new">Create New (Empty)</button>
          <div style="flex: 1;"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert" disabled>Insert</button>
        </div>
      </div>
    `;

    const stepList = dialog.querySelector<HTMLElement>('#stencil-step-list')!;
    const stepVersions = dialog.querySelector<HTMLElement>('#stencil-step-versions')!;
    const list = dialog.querySelector<HTMLElement>('#stencil-list')!;
    const versionList = dialog.querySelector<HTMLElement>('#stencil-version-list')!;
    const versionTitle = dialog.querySelector<HTMLElement>('#stencil-version-title')!;
    const searchInput = dialog.querySelector<HTMLInputElement>('#stencil-search')!;
    const closeBtn = dialog.querySelector<HTMLElement>('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!;
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!;
    const createNewBtn = dialog.querySelector<HTMLElement>('.create-new')!;
    const backBtn = dialog.querySelector<HTMLElement>('#stencil-back')!;

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

        const tags =
          stencil.tags.length > 0
            ? stencil.tags.map((t) => `<span class="stencil-picker-tag">${t}</span>`).join('')
            : '';
        const versionLabel = stencil.latestPublishedVersion
          ? `v${stencil.latestPublishedVersion}`
          : stencil.latestVersion
            ? `v${stencil.latestVersion} (draft)`
            : '<span class="text-muted">no versions</span>';

        card.innerHTML = `
          <div class="stencil-picker-card-name">${stencil.name}</div>
          <div class="stencil-picker-card-meta">
            <span class="stencil-picker-card-version">${versionLabel}</span>
            ${tags ? `<span class="stencil-picker-card-tags">${tags}</span>` : ''}
          </div>
          ${stencil.description ? `<div class="stencil-picker-card-desc">${stencil.description}</div>` : ''}
        `;

        card.addEventListener('click', () => {
          selectedStencil = stencil;
          showVersionPicker(stencil);
        });

        list.appendChild(card);
      }
    }

    // ── Step 2: Version picker ──

    async function showVersionPicker(stencil: StencilSummary) {
      stepList.style.display = 'none';
      stepVersions.style.display = '';
      versionTitle.textContent = `Versions for "${stencil.name}"`;
      versionList.innerHTML = '<div class="stencil-picker-loading">Loading versions...</div>';
      insertBtn.disabled = true;
      selectedVersion = null;

      try {
        const versions = await callbacks.listVersions(stencil.id);
        renderVersionList(versions);
      } catch {
        versionList.innerHTML = '<div class="stencil-picker-empty">Failed to load versions.</div>';
      }
    }

    function renderVersionList(versions: StencilVersionSummary[]) {
      if (versions.length === 0) {
        versionList.innerHTML =
          '<div class="stencil-picker-empty">No versions available.</div>';
        return;
      }

      // Sort: draft first, then published (newest first), then archived
      const sorted = [...versions].sort((a, b) => {
        const order = { draft: 0, published: 1, archived: 2 };
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
          versionList.querySelectorAll('.stencil-picker-card').forEach((c) => c.classList.remove('selected'));
          card.classList.add('selected');
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
      stepVersions.style.display = 'none';
      stepList.style.display = '';
      insertBtn.disabled = true;
      selectedVersion = null;
    }

    // ── Insert ──

    async function doInsert() {
      if (!selectedStencil || !selectedVersion) return;
      insertBtn.disabled = true;
      insertBtn.textContent = 'Loading...';

      const versionInfo = await callbacks.getStencilVersion(
        selectedStencil.id,
        selectedVersion.version,
      );
      if (!versionInfo) {
        insertBtn.textContent = 'Insert';
        insertBtn.disabled = false;
        return;
      }
      close({ action: 'use-existing', versionInfo });
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
    insertBtn.addEventListener('click', () => doInsert());
    createNewBtn.addEventListener('click', () => close({ action: 'create-new' }));
    backBtn.addEventListener('click', () => showStencilList());

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
