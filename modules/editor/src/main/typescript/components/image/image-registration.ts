/**
 * Image component definition for the component registry.
 *
 * Leaf node (no children) that displays an image from the asset manager.
 * Uses an asset picker dialog for selection at insert time.
 */

import type { ComponentDefinition } from '../../engine/registry.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import { openAssetPickerDialog, type AssetPickerCallbacks } from './asset-picker-dialog.js'
import { html } from 'lit'

/** Layout-only style properties available on image nodes. */
const IMAGE_STYLES = ['padding', 'margin']

export interface ImageOptions {
  assetPicker: AssetPickerCallbacks
  contentUrlPattern: string
}

function resolveContentUrl(pattern: string, assetId: string): string {
  return pattern.replace('{assetId}', assetId)
}

export function createImageDefinition(options: ImageOptions): ComponentDefinition {
  return {
    type: 'image',
    label: 'Image',
    icon: 'image',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: IMAGE_STYLES,
    inspector: [
      { key: 'alt', label: 'Alt Text', type: 'text' },
      {
        key: 'objectFit', label: 'Object Fit', type: 'select',
        options: [
          { label: 'Contain', value: 'contain' },
          { label: 'Cover', value: 'cover' },
          { label: 'Fill', value: 'fill' },
          { label: 'None', value: 'none' },
        ],
        defaultValue: 'contain',
      },
      { key: 'width', label: 'Width', type: 'text' },
      { key: 'height', label: 'Height', type: 'text' },
    ],
    defaultProps: {
      assetId: null,
      alt: '',
      objectFit: 'contain',
      width: '',
      height: '',
    },

    renderCanvas: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine

      const pickAsset = async (e: Event) => {
        e.stopPropagation()
        const asset = await openAssetPickerDialog(options.assetPicker)
        if (!asset) return
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: {
            ...node.props,
            assetId: asset.id,
            alt: asset.name,
            width: asset.width ? `${asset.width}px` : '',
            height: asset.height ? `${asset.height}px` : '',
          },
        })
      }

      const assetId = node.props?.assetId as string | null
      if (!assetId) {
        return html`<div class="canvas-image-placeholder" @click=${pickAsset} style="cursor: pointer" title="Click to select an image">
          <span class="canvas-image-placeholder-icon">&#128247;</span>
          <span>Click to select an image</span>
        </div>`
      }
      const src = resolveContentUrl(options.contentUrlPattern, assetId)
      const alt = (node.props?.alt as string) || ''
      const objectFit = (node.props?.objectFit as string) || 'contain'
      const width = node.props?.width as string | undefined
      const height = node.props?.height as string | undefined

      const hasDimensions = width || height
      const wrapperStyle = `cursor: pointer;${width ? ` width: ${width};` : ''}${height ? ` height: ${height};` : ''}`
      const imgStyle = hasDimensions
        ? `object-fit: ${objectFit}; width: 100%; height: 100%;`
        : `object-fit: ${objectFit};`

      return html`<div class="canvas-image-wrapper" @click=${pickAsset} style=${wrapperStyle} title="Click to change image">
        <img
          src="${src}"
          alt="${alt}"
          style=${imgStyle}
          class="canvas-image"
        />
      </div>`
    },

    onBeforeInsert: async () => {
      const asset = await openAssetPickerDialog(options.assetPicker)
      if (!asset) return null

      return {
        assetId: asset.id,
        alt: asset.name,
        width: asset.width ? `${asset.width}px` : '',
        height: asset.height ? `${asset.height}px` : '',
      }
    },
  }
}
