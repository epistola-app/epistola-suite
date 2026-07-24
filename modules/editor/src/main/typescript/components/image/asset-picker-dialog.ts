// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Asset picker dialog — modal for selecting or uploading images.
 *
 * Uses native <dialog> element, same pattern as table-dialog.ts.
 * Returns a Promise that resolves with the selected asset info or null.
 *
 * A catalog chooser lets the author pick images from any catalog the tenant
 * can access (defaulting to the template's own catalog). The chosen asset
 * carries its `catalogKey`, so the reference works across catalogs. Uploads
 * are only possible into AUTHORED catalogs, so the upload zone is disabled
 * while a read-only (SUBSCRIBED) catalog is selected.
 */

export interface AssetInfo {
  id: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  width: number | null;
  height: number | null;
  catalogKey: string;
  contentUrl: string;
}

export interface CatalogInfo {
  key: string;
  name: string;
  /** AUTHORED catalogs accept uploads; SUBSCRIBED ones are read-only. */
  type: string;
}

export interface AssetPickerCallbacks {
  /** The template's own catalog — the chooser's initial selection. */
  defaultCatalogKey: string;
  listCatalogs: () => Promise<CatalogInfo[]>;
  listAssets: (catalogKey: string) => Promise<AssetInfo[]>;
  uploadAsset: (file: File, catalogKey: string) => Promise<AssetInfo>;
}

export function openAssetPickerDialog(callbacks: AssetPickerCallbacks): Promise<AssetInfo | null> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'asset-picker-dialog';

    dialog.innerHTML = `
      <div class="asset-picker-content">
        <div class="asset-picker-header">
          <h3>Select Image</h3>
          <button type="button" class="asset-picker-close" aria-label="Close">&times;</button>
        </div>

        <div class="asset-picker-catalog">
          <label class="ep-label asset-picker-catalog-label" for="asset-picker-catalog">Catalog</label>
          <select class="ep-select ep-select-sm form-control-auto asset-picker-catalog-select" id="asset-picker-catalog"></select>
        </div>

        <div class="asset-picker-upload-zone" id="asset-picker-upload">
          <p>Drop image here or <label class="asset-picker-upload-link" for="asset-picker-file">browse</label></p>
          <p class="asset-picker-upload-hint">PNG, JPEG, WebP, SVG — max 5MB</p>
          <p class="asset-picker-upload-error" id="asset-picker-upload-error" role="alert" aria-live="polite"></p>
          <input type="file" id="asset-picker-file"
                 accept="image/png,image/jpeg,image/webp,image/svg+xml"
                 style="display: none;" />
        </div>

        <div class="asset-picker-grid" id="asset-picker-grid">
          <div class="asset-picker-loading">Loading assets...</div>
        </div>

        <div class="asset-picker-footer">
          <button type="button" class="asset-picker-btn cancel">Cancel</button>
          <button type="button" class="asset-picker-btn insert" disabled>Insert</button>
        </div>
      </div>
    `;

    const catalogSelect = dialog.querySelector<HTMLSelectElement>('.asset-picker-catalog-select')!;
    const grid = dialog.querySelector<HTMLElement>('#asset-picker-grid')!;
    const uploadZone = dialog.querySelector<HTMLElement>('#asset-picker-upload')!;
    const fileInput = dialog.querySelector<HTMLInputElement>('#asset-picker-file')!;
    const uploadError = dialog.querySelector<HTMLElement>('#asset-picker-upload-error')!;
    const closeBtn = dialog.querySelector<HTMLElement>('.asset-picker-close')!;
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!;
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!;

    let selectedAsset: AssetInfo | null = null;
    let currentCatalogKey = callbacks.defaultCatalogKey;
    const catalogTypeByKey = new Map<string, string>();

    const close = (result: AssetInfo | null) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    function renderGrid(assets: AssetInfo[]) {
      if (assets.length === 0) {
        grid.innerHTML = '<div class="asset-picker-empty">No images in this catalog yet.</div>';
        return;
      }

      grid.innerHTML = '';
      for (const asset of assets) {
        const card = document.createElement('div');
        card.className = 'asset-picker-card';
        card.dataset.assetId = asset.id;
        card.innerHTML = `
          <div class="asset-picker-card-img">
            <img src="${asset.contentUrl}" alt="${asset.name}" loading="lazy" />
          </div>
          <div class="asset-picker-card-name" title="${asset.name}">${asset.name}</div>
        `;
        card.addEventListener('click', () => {
          // Deselect previous
          grid
            .querySelectorAll('.asset-picker-card')
            .forEach((c) => c.classList.remove('selected'));
          card.classList.add('selected');
          selectedAsset = asset;
          insertBtn.disabled = false;
        });
        card.addEventListener('dblclick', () => {
          selectedAsset = asset;
          close(selectedAsset);
        });
        grid.appendChild(card);
      }
    }

    // Bumped on every load so a slow earlier response can't overwrite a newer one.
    let assetLoadSeq = 0;

    /** Load the images for the active catalog, resetting any current selection. */
    function loadAssets(catalogKey: string) {
      const loadSeq = ++assetLoadSeq;
      grid.innerHTML = '<div class="asset-picker-loading">Loading assets...</div>';
      selectedAsset = null;
      insertBtn.disabled = true;
      callbacks
        .listAssets(catalogKey)
        .then((assets) => {
          // Drop the response if the author has since switched catalogs.
          if (loadSeq !== assetLoadSeq) return;
          renderGrid(assets);
        })
        .catch(() => {
          if (loadSeq !== assetLoadSeq) return;
          grid.innerHTML = '<div class="asset-picker-empty">Failed to load assets.</div>';
        });
    }

    /** Uploads need an AUTHORED catalog; lock the zone for read-only catalogs. */
    function applyUploadAvailability(catalogKey: string) {
      const readOnly =
        catalogTypeByKey.get(catalogKey) !== undefined &&
        catalogTypeByKey.get(catalogKey) !== 'AUTHORED';
      uploadZone.classList.toggle('disabled', readOnly);
      fileInput.disabled = readOnly;
    }

    // Populate the catalog chooser, defaulting to the template's catalog.
    callbacks
      .listCatalogs()
      .then((catalogs) => {
        catalogSelect.innerHTML = '';
        for (const catalog of catalogs) {
          catalogTypeByKey.set(catalog.key, catalog.type);
          const option = document.createElement('option');
          option.value = catalog.key;
          option.textContent = catalog.name;
          catalogSelect.appendChild(option);
        }
        if (catalogs.some((c) => c.key === currentCatalogKey)) {
          catalogSelect.value = currentCatalogKey;
        } else if (catalogs.length > 0) {
          currentCatalogKey = catalogs[0].key;
          catalogSelect.value = currentCatalogKey;
        }
        applyUploadAvailability(currentCatalogKey);
        // Load only once the effective catalog is settled, so the grid and the
        // chooser never disagree.
        loadAssets(currentCatalogKey);
      })
      .catch(() => {
        // Chooser stays empty; fall back to loading the template's catalog.
        // We couldn't enumerate catalogs, so the default catalog's type is
        // unknown — disable uploads rather than assume it's editable (the
        // backend guards too). loadAssets still shows whatever it can read.
        uploadZone.classList.add('disabled');
        fileInput.disabled = true;
        loadAssets(currentCatalogKey);
      });

    catalogSelect.addEventListener('change', () => {
      currentCatalogKey = catalogSelect.value;
      applyUploadAvailability(currentCatalogKey);
      loadAssets(currentCatalogKey);
    });

    // Upload handling
    const handleUpload = async (file: File) => {
      uploadError.textContent = '';
      uploadZone.classList.add('uploading');
      // Pin the target catalog so a switch mid-upload can't redirect the result.
      const uploadCatalogKey = currentCatalogKey;
      try {
        const asset = await callbacks.uploadAsset(file, uploadCatalogKey);
        // Refresh the grid and auto-select the new asset
        const assets = await callbacks.listAssets(uploadCatalogKey);
        // The author switched catalogs while uploading — don't render here.
        if (uploadCatalogKey !== currentCatalogKey) return;
        renderGrid(assets);
        // Select the newly uploaded asset
        const newCard = grid.querySelector(`[data-asset-id="${asset.id}"]`);
        if (newCard) {
          newCard.classList.add('selected');
          selectedAsset = asset;
          insertBtn.disabled = false;
        }
      } catch (err) {
        console.error('Asset upload failed:', err);
        uploadError.textContent = err instanceof Error ? err.message : 'Failed to upload asset.';
      } finally {
        uploadZone.classList.remove('uploading');
      }
    };

    fileInput.addEventListener('change', () => {
      const file = fileInput.files?.[0];
      if (file) handleUpload(file);
      fileInput.value = '';
    });

    uploadZone.addEventListener('dragover', (e) => {
      e.preventDefault();
      if (fileInput.disabled) return;
      uploadZone.classList.add('dragover');
    });
    uploadZone.addEventListener('dragleave', () => {
      uploadZone.classList.remove('dragover');
    });
    uploadZone.addEventListener('drop', (e) => {
      e.preventDefault();
      uploadZone.classList.remove('dragover');
      if (fileInput.disabled) return;
      const file = e.dataTransfer?.files[0];
      if (file) handleUpload(file);
    });

    // Button actions
    closeBtn.addEventListener('click', () => close(null));
    cancelBtn.addEventListener('click', () => close(null));
    insertBtn.addEventListener('click', () => close(selectedAsset));

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
  });
}
