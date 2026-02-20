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

/** Parse a CSS px value, returning the numeric part or null for non-px / empty values. */
function parsePx(value: unknown): number | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed.endsWith('px')) return null
  const num = parseFloat(trimmed)
  return Number.isFinite(num) && num > 0 ? num : null
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

    onPropChange: (key, value, props) => {
      const oldWidth = parsePx(props.width)
      const oldHeight = parsePx(props.height)

      if (key === 'width' && oldHeight && oldWidth) {
        const newWidth = parsePx(value)
        if (newWidth) {
          props.height = `${Math.round(oldHeight * (newWidth / oldWidth))}px`
        }
      } else if (key === 'height' && oldWidth && oldHeight) {
        const newHeight = parsePx(value)
        if (newHeight) {
          props.width = `${Math.round(oldWidth * (newHeight / oldHeight))}px`
        }
      }

      return props
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

      const imgStyle = [
        `object-fit: ${objectFit}`,
        width ? `width: ${width}` : '',
        height ? `height: ${height}` : '',
      ].filter(Boolean).join('; ') + ';'

      return html`<div class="canvas-image-wrapper" @click=${pickAsset} style="cursor: pointer" title="Click to change image">
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
