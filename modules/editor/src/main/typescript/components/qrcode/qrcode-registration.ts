import { html } from 'lit';
import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import { openAssetPickerDialog, type AssetPickerCallbacks } from '../image/asset-picker-dialog.js';
import './EpistolaQrCodePreview.js';

const QR_CODE_STYLES = ['padding', 'margin'];

type QrCodeType = 'standard' | 'logo';

export interface QrCodeOptions {
  assetPicker?: AssetPickerCallbacks;
  contentUrlPattern?: string;
}

function resolveContentUrl(pattern: string, assetId: string): string {
  return pattern.replace('{assetId}', assetId);
}

export function createQrCodeDefinition(options?: QrCodeOptions): ComponentDefinition {
  return {
    type: 'qrcode',
    label: 'QR Code',
    icon: 'qr-code',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: QR_CODE_STYLES,
    defaultStyles: { marginBottom: '1.5sp' },
    inspector: [
      { key: 'value.raw', label: 'Value', type: 'expression' },
      { key: 'size', label: 'Size', type: 'unit', units: ['pt', 'sp'], defaultValue: '120pt' },
      {
        key: 'qrType',
        label: 'Type',
        type: 'select',
        options: [
          { label: 'Standard', value: 'standard' },
          { label: 'With Logo', value: 'logo' },
        ],
        defaultValue: 'standard',
      },
    ],
    defaultProps: {
      value: { raw: '', language: 'jsonata' },
      size: '120pt',
      qrType: 'standard',
      logoAssetId: null,
    },
    renderInspectorAfterProps: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;
      const qrType = ((node.props?.qrType as string | undefined) ?? 'standard') as QrCodeType;
      const logoAssetId = (node.props?.logoAssetId as string | null | undefined) ?? null;

      if (qrType !== 'logo') return null;

      const pickLogo = async () => {
        if (!options?.assetPicker) return;
        const asset = await openAssetPickerDialog(options.assetPicker, {
          acceptedMimeTypes: ['image/png', 'image/jpeg', 'image/webp'],
        });
        if (!asset) return;
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: {
            ...node.props,
            logoAssetId: asset.id,
          },
        });
      };

      const removeLogo = () => {
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: {
            ...node.props,
            logoAssetId: null,
          },
        });
      };

      return html`
        <div class="inspector-field">
          <span class="inspector-field-label">Logo</span>
          <div class="qrcode-logo-actions">
            <button class="ep-btn-primary btn-sm" type="button" @click=${pickLogo}>
              ${logoAssetId ? 'Change' : 'Select'}
            </button>
            ${logoAssetId
              ? html`<button class="ep-btn-outline btn-sm" type="button" @click=${removeLogo}>
                  Remove
                </button>`
              : null}
          </div>
        </div>
      `;
    },
    renderCanvas: ({ node, engine: eng }) => {
      const expression = (node.props?.value as { raw?: string } | undefined)?.raw ?? '';
      const size = (node.props?.size as string | undefined) ?? '120pt';
      const qrType = ((node.props?.qrType as string | undefined) ?? 'standard') as QrCodeType;
      const logoAssetId = (node.props?.logoAssetId as string | null | undefined) ?? null;
      const logoSrc =
        qrType === 'logo' && logoAssetId && options?.contentUrlPattern
          ? resolveContentUrl(options.contentUrlPattern, logoAssetId)
          : null;

      return html`
        <div class="qrcode-canvas">
          <epistola-qrcode-preview
            .engine=${eng}
            .nodeId=${node.id}
            .expression=${expression}
            .size=${size}
            .qrType=${qrType}
            .logoSrc=${logoSrc}
          ></epistola-qrcode-preview>
        </div>
      `;
    },
  };
}
