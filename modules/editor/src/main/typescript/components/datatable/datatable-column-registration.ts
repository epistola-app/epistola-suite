/**
 * Datatable Column component definition for the component registry.
 *
 * A datatable-column is a child-only node that lives inside a datatable's
 * "columns" slot. Each column defines a header label, a width, and a "body"
 * slot whose content is repeated per data row at render time.
 *
 * Hidden from the palette — users add columns via the datatable inspector.
 */

import type { ComponentDefinition } from '../../engine/registry.js';

export function createDatatableColumnDefinition(): ComponentDefinition {
  return {
    type: 'datatable-column',
    label: 'Data Table Column',
    category: 'layout',
    hidden: true,
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: ['padding', 'backgroundColor', 'border'],
    inspector: [
      { key: 'header', label: 'Header', type: 'text' },
      { key: 'width', label: 'Width', type: 'number', defaultValue: 33 },
    ],
    defaultProps: { header: '', width: 33 },

    examples: [
      {
        name: 'minimal',
        description:
          'A single datatable-column with a literal header and an empty body slot. Standalone fragment — embed inside a datatable\'s "columns" slot for it to render.',
        fragment: {
          rootNodeId: 'n-dtcol-minimal',
          nodes: {
            'n-dtcol-minimal': {
              id: 'n-dtcol-minimal',
              type: 'datatable-column',
              slots: ['s-dtcol-minimal-body'],
              props: { header: 'Description', width: 50 },
            },
          },
          slots: {
            's-dtcol-minimal-body': {
              id: 's-dtcol-minimal-body',
              nodeId: 'n-dtcol-minimal',
              name: 'body',
              children: [],
            },
          },
        },
      },
    ],
  };
}
