import { html } from 'lit';
import type { ComponentDefinition } from '../../engine/registry.js';
import './EpistolaQrCodePreview.js';

const QR_CODE_STYLES = ['padding', 'margin'];

export function createQrCodeDefinition(): ComponentDefinition {
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
    ],
    defaultProps: {
      value: { raw: '', language: 'jsonata' },
      size: '120pt',
    },
    renderCanvas: ({ node, engine: eng }) => {
      const expression = (node.props?.value as { raw?: string } | undefined)?.raw ?? '';
      const size = (node.props?.size as string | undefined) ?? '120pt';

      return html`
        <div class="qrcode-canvas">
          <epistola-qrcode-preview
            .engine=${eng}
            .nodeId=${node.id}
            .expression=${expression}
            .size=${size}
          ></epistola-qrcode-preview>
        </div>
      `;
    },
  };
}
