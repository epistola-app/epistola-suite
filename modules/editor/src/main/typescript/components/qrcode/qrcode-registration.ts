// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

    examples: [
      {
        name: 'data-bound-link',
        description:
          'QR code whose value is taken from a data field — e.g. a payment URL. The value prop is an expression, not a literal string.',
        fragment: {
          rootNodeId: 'n-qr-link',
          nodes: {
            'n-qr-link': {
              id: 'n-qr-link',
              type: 'qrcode',
              slots: [],
              props: {
                value: { raw: 'paymentLink', language: 'jsonata' },
                size: '80pt',
              },
            },
          },
          slots: {},
        },
      },
      {
        name: 'static-string',
        description:
          'QR code with a literal value. Use a JSONata string literal (single quotes) inside the raw expression.',
        fragment: {
          rootNodeId: 'n-qr-static',
          nodes: {
            'n-qr-static': {
              id: 'n-qr-static',
              type: 'qrcode',
              slots: [],
              props: {
                value: { raw: "'https://epistola.app'", language: 'jsonata' },
                size: '120pt',
              },
            },
          },
          slots: {},
        },
      },
    ],
  };
}
