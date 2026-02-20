/**
 * Asset picker dialog — modal for selecting or uploading images.
 *
 * Uses native <dialog> element, same pattern as table-dialog.ts.
 * Returns a Promise that resolves with the selected asset info or null.
 */

export interface AssetInfo {
  id: string
  name: string
  mediaType: string
  sizeBytes: number
  width: number | null
  height: number | null
  contentUrl: string
}

export interface AssetPickerCallbacks {
  listAssets: () => Promise<AssetInfo[]>
  uploadAsset: (file: File) => Promise<AssetInfo>
}

export function openAssetPickerDialog(callbacks: AssetPickerCallbacks): Promise<AssetInfo | null> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog')
    dialog.className = 'asset-picker-dialog'

    dialog.innerHTML = `
      <div class="asset-picker-content">
        <div class="asset-picker-header">
          <h3>Select Image</h3>
          <button type="button" class="asset-picker-close" aria-label="Close">&times;</button>
        </div>

        <div class="asset-picker-upload-zone" id="asset-picker-upload">
          <p>Drop image here or <label class="asset-picker-upload-link" for="asset-picker-file">browse</label></p>
          <p class="asset-picker-upload-hint">PNG, JPEG, WebP, SVG — max 5MB</p>
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
    `

    const grid = dialog.querySelector<HTMLElement>('#asset-picker-grid')!
    const uploadZone = dialog.querySelector<HTMLElement>('#asset-picker-upload')!
    const fileInput = dialog.querySelector<HTMLInputElement>('#asset-picker-file')!
    const closeBtn = dialog.querySelector<HTMLElement>('.asset-picker-close')!
    const cancelBtn = dialog.querySelector<HTMLElement>('.cancel')!
    const insertBtn = dialog.querySelector<HTMLButtonElement>('.insert')!

    let selectedAsset: AssetInfo | null = null

    const close = (result: AssetInfo | null) => {
      dialog.close()
      dialog.remove()
      resolve(result)
    }

    function renderGrid(assets: AssetInfo[]) {
      if (assets.length === 0) {
        grid.innerHTML = '<div class="asset-picker-empty">No assets yet. Upload an image above.</div>'
        return
      }

      grid.innerHTML = ''
      for (const asset of assets) {
        const card = document.createElement('div')
        card.className = 'asset-picker-card'
        card.dataset.assetId = asset.id
        card.innerHTML = `
          <div class="asset-picker-card-img">
            <img src="${asset.contentUrl}" alt="${asset.name}" loading="lazy" />
          </div>
          <div class="asset-picker-card-name" title="${asset.name}">${asset.name}</div>
        `
        card.addEventListener('click', () => {
          // Deselect previous
          grid.querySelectorAll('.asset-picker-card').forEach(c => c.classList.remove('selected'))
          card.classList.add('selected')
          selectedAsset = asset
          insertBtn.disabled = false
        })
        card.addEventListener('dblclick', () => {
          selectedAsset = asset
          close(selectedAsset)
        })
        grid.appendChild(card)
      }
    }

    // Load existing assets
    callbacks.listAssets().then(assets => {
      renderGrid(assets)
    }).catch(() => {
      grid.innerHTML = '<div class="asset-picker-empty">Failed to load assets.</div>'
    })

    // Upload handling
    const handleUpload = async (file: File) => {
      uploadZone.classList.add('uploading')
      try {
        const asset = await callbacks.uploadAsset(file)
        // Refresh the grid and auto-select the new asset
        const assets = await callbacks.listAssets()
        renderGrid(assets)
        // Select the newly uploaded asset
        const newCard = grid.querySelector(`[data-asset-id="${asset.id}"]`)
        if (newCard) {
          newCard.classList.add('selected')
          selectedAsset = asset
          insertBtn.disabled = false
        }
      } catch (err) {
        console.error('Asset upload failed:', err)
      } finally {
        uploadZone.classList.remove('uploading')
      }
    }

    fileInput.addEventListener('change', () => {
      const file = fileInput.files?.[0]
      if (file) handleUpload(file)
      fileInput.value = ''
    })

    uploadZone.addEventListener('dragover', (e) => {
      e.preventDefault()
      uploadZone.classList.add('dragover')
    })
    uploadZone.addEventListener('dragleave', () => {
      uploadZone.classList.remove('dragover')
    })
    uploadZone.addEventListener('drop', (e) => {
      e.preventDefault()
      uploadZone.classList.remove('dragover')
      const file = e.dataTransfer?.files[0]
      if (file) handleUpload(file)
    })

    // Button actions
    closeBtn.addEventListener('click', () => close(null))
    cancelBtn.addEventListener('click', () => close(null))
    insertBtn.addEventListener('click', () => close(selectedAsset))

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close(null)
      }
    })
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close(null)
    })

    document.body.appendChild(dialog)
    dialog.showModal()
  })
}
