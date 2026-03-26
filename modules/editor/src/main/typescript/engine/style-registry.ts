/**
 * Default style registry — defines all style properties available in the editor.
 *
 * This is the single source of truth for what style properties exist,
 * how they should be edited, and which ones cascade from document styles.
 */

import type { StyleRegistry } from '@epistola.app/editor-model/generated/style-registry'

export const defaultStyleRegistry: StyleRegistry = {
  groups: [
    {
      name: 'typography',
      label: 'Typography',
      properties: [
        {
          key: 'fontFamily',
          label: 'Font',
          type: 'select',
          inheritable: true,
          options: [
            { label: 'Sans (Helvetica)', value: 'Helvetica, Arial, sans-serif' },
            { label: 'Serif (Times)', value: '"Times New Roman", Georgia, serif' },
            { label: 'Mono (Courier)', value: '"Courier New", monospace' },
          ],
        },
        {
          key: 'fontSize',
          label: 'Size',
          type: 'unit',
          inheritable: true,
          units: ['pt', 'sp'],
        },
        {
          key: 'fontWeight',
          label: 'Weight',
          type: 'select',
          inheritable: true,
          options: [
            { label: 'Normal', value: '400' },
            { label: 'Medium', value: '500' },
            { label: 'Semi Bold', value: '600' },
            { label: 'Bold', value: '700' },
          ],
        },
        { key: 'color', label: 'Color', type: 'color', inheritable: true },
        {
          key: 'lineHeight',
          label: 'Line Height',
          type: 'number',
          inheritable: true,
        },
        {
          key: 'letterSpacing',
          label: 'Letter Spacing',
          type: 'unit',
          inheritable: true,
          units: ['pt', 'sp'],
        },
        {
          key: 'textAlign',
          label: 'Text Align',
          type: 'select',
          inheritable: true,
          options: [
            { label: 'Left', value: 'left' },
            { label: 'Center', value: 'center' },
            { label: 'Right', value: 'right' },
            { label: 'Justify', value: 'justify' },
          ],
        },
      ],
    },
    {
      name: 'spacing',
      label: 'Spacing',
      properties: [
        { key: 'padding', label: 'Padding', type: 'spacing', units: ['sp', 'pt'] },
        { key: 'margin', label: 'Margin', type: 'spacing', units: ['sp', 'pt'] },
      ],
    },
    {
      name: 'background',
      label: 'Background',
      properties: [
        { key: 'backgroundColor', label: 'Background', type: 'color' },
      ],
    },
    {
      name: 'borders',
      label: 'Borders',
      properties: [
        { key: 'borderWidth', label: 'Width', type: 'unit', units: ['pt', 'sp'] },
        {
          key: 'borderStyle',
          label: 'Style',
          type: 'select',
          options: [
            { label: 'None', value: 'none' },
            { label: 'Solid', value: 'solid' },
            { label: 'Dashed', value: 'dashed' },
            { label: 'Dotted', value: 'dotted' },
          ],
        },
        { key: 'borderColor', label: 'Color', type: 'color' },
        { key: 'borderRadius', label: 'Radius', type: 'unit', units: ['pt', 'sp'] },
      ],
    },
  ],
}
