/**
 * Image component definition for the component registry.
 *
 * Leaf node (no children) that displays an image from the asset manager.
 * Uses an asset picker dialog for selection at insert time.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import { openAssetPickerDialog, type AssetPickerCallbacks } from './asset-picker-dialog.js';
import { html } from 'lit';

/** Layout-only style properties available on image nodes. */
const IMAGE_STYLES = ['padding', 'margin'];

export interface ImageOptions {
  assetPicker?: AssetPickerCallbacks;
  contentUrlPattern?: string;
}

function resolveContentUrl(pattern: string | undefined, assetId: string): string {
  return (pattern ?? '/assets/{assetId}').replace('{assetId}', assetId);
}

/** Parse a pt value, returning the numeric part or null for non-pt / empty values. */
function parsePt(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed.endsWith('pt')) return null;
  const num = parseFloat(trimmed);
  return Number.isFinite(num) && num > 0 ? num : null;
}

/** Convert image pixel dimensions to pt (1px = 0.75pt). */
function pxToPt(px: number): number {
  return Math.round(px * 0.75);
}

export function createImageDefinition(options?: ImageOptions): ComponentDefinition {
  return {
    type: 'image',
    label: 'Image',
    icon: 'image',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: IMAGE_STYLES,
    defaultStyles: { marginBottom: '1.5sp' },
    inspector: [
      { key: 'alt', label: 'Alt Text', type: 'text' },
      { key: 'width', label: 'Width', type: 'unit', units: ['pt', 'sp', '%'] },
      { key: 'height', label: 'Height', type: 'unit', units: ['pt', 'sp', '%'] },
      { key: 'aspectRatioLocked', label: 'Lock Aspect Ratio', type: 'boolean', defaultValue: true },
    ],
    defaultProps: {
      assetId: null,
      alt: '',
      width: '',
      height: '',
      aspectRatioLocked: true,
    },

    onPropChange: (key, value, props) => {
      if (!props.aspectRatioLocked) return props;

      const oldWidth = parsePt(props.width);
      const oldHeight = parsePt(props.height);

      if (key === 'width' && oldHeight && oldWidth) {
        const newWidth = parsePt(value);
        if (newWidth) {
          props.height = `${Math.round(oldHeight * (newWidth / oldWidth))}pt`;
        }
      } else if (key === 'height' && oldWidth && oldHeight) {
        const newHeight = parsePt(value);
        if (newHeight) {
          props.width = `${Math.round(oldWidth * (newHeight / oldHeight))}pt`;
        }
      }

      return props;
    },

    examples: [
      {
        name: 'placeholder',
        description:
          'An empty image node with no assigned asset. The assetId is null, so the renderer shows a placeholder. Set assetId to a valid asset reference and width/height to render the actual image.',
        fragment: {
          rootNodeId: 'n-image-placeholder',
          nodes: {
            'n-image-placeholder': {
              id: 'n-image-placeholder',
              type: 'image',
              slots: [],
              props: {
                assetId: null,
                alt: '',
                width: '',
                height: '',
                aspectRatioLocked: true,
              },
            },
          },
          slots: {},
        },
      },
      {
        name: 'with-asset',
        description:
          'An image node referencing a real asset (the Epistola logo from the demo catalog: slug 01966a00-0000-7000-8000-000000000001). Width and height are set in points — the renderer fetches the asset binary when producing the PDF.',
        fragment: {
          rootNodeId: 'n-image-logo',
          nodes: {
            'n-image-logo': {
              id: 'n-image-logo',
              type: 'image',
              slots: [],
              props: {
                assetId: '01966a00-0000-7000-8000-000000000001',
                alt: 'Epistola Logo',
                width: '80pt',
                height: '20pt',
                aspectRatioLocked: true,
              },
            },
          },
          slots: {},
        },
      },
    ],

    renderCanvas: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;

      const pickAsset = async (e: Event) => {
        e.stopPropagation();
        if (!options?.assetPicker) return;
        const asset = await openAssetPickerDialog(options.assetPicker);
        if (!asset) return;
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: {
            ...node.props,
            assetId: asset.id,
            alt: asset.name,
            width: asset.width ? `${pxToPt(asset.width)}pt` : '',
            height: asset.height ? `${pxToPt(asset.height)}pt` : '',
          },
        });
      };

      const assetId = node.props?.assetId as string | null;
      if (!assetId) {
        const hasPicker = options?.assetPicker != null;
        return html`<div
          class="canvas-image-placeholder"
          @click=${hasPicker ? pickAsset : undefined}
          style=${hasPicker ? 'cursor: pointer' : undefined}
          title=${hasPicker ? 'Click to select an image' : 'Image placeholder (no asset selected)'}
        >
          <span class="canvas-image-placeholder-icon">&#128247;</span>
          <span>${hasPicker ? 'Click to select an image' : 'Image placeholder'}</span>
        </div>`;
      }
      const src = resolveContentUrl(options?.contentUrlPattern, assetId);
      const alt = (node.props?.alt as string) || '';
      const width = node.props?.width as string | undefined;
      const height = node.props?.height as string | undefined;

      const imgStyle =
        [width ? `width: ${width}` : '', height ? `height: ${height}` : '']
          .filter(Boolean)
          .join('; ') + ';';

      const hasPicker = options?.assetPicker != null;
      return html`<div
        class="canvas-image-wrapper"
        @click=${hasPicker ? pickAsset : undefined}
        style=${hasPicker ? 'cursor: pointer' : undefined}
        title=${hasPicker ? 'Click to change image' : alt}
      >
        <img src="${src}" alt="${alt}" style=${imgStyle} class="canvas-image" />
      </div>`;
    },

    onBeforeInsert: options?.assetPicker
      ? async () => {
          const asset = await openAssetPickerDialog(options.assetPicker!);
          if (!asset) return null;

          return {
            assetId: asset.id,
            alt: asset.name,
            width: asset.width ? `${pxToPt(asset.width)}pt` : '',
            height: asset.height ? `${pxToPt(asset.height)}pt` : '',
          };
        }
      : undefined,
  };
}
