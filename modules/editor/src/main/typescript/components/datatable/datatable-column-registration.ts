/**
 * Datatable Column component definition for the component registry.
 *
 * A datatable-column is a child-only node that lives inside a datatable's
 * "columns" slot. Each column defines a header label, a width, and a "body"
 * slot whose content is repeated per data row at render time.
 *
 * Hidden from the palette — users add columns via the datatable inspector.
 */

import type { ComponentDefinition } from '../../engine/registry.js'

export function createDatatableColumnDefinition(): ComponentDefinition {
  return {
    type: 'datatable-column',
    label: 'Data Table Column',
    category: 'layout',
    hidden: true,
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: [
      'padding', 'backgroundColor',
      'borderWidth', 'borderStyle', 'borderColor',
    ],
    inspector: [
      { key: 'header', label: 'Header', type: 'text' },
      { key: 'width', label: 'Width', type: 'number', defaultValue: 33 },
    ],
    defaultProps: { header: '', width: 33 },
  }
}
