/**
 * Table component definition for the component registry.
 *
 * Exports a factory that creates the ComponentDefinition. The definition
 * uses createInitialSlots to generate `rows * columns` cell slots.
 */

import type { NodeId, SlotId, Slot } from '../../types/index.js'
import type { ComponentDefinition } from '../../engine/registry.js'
import { cellSlotName } from './table-utils.js'
import { nanoid } from 'nanoid'

/** Layout style properties available on table nodes. */
const LAYOUT_STYLES = [
  'padding', 'margin',
  'backgroundColor',
  'borderWidth', 'borderStyle', 'borderColor', 'borderRadius',
]

export const TABLE_DEFAULT_PROPS = {
  rows: 2,
  columns: 2,
  columnWidths: [50, 50],
  borderStyle: 'all',
  headerRows: 0,
  merges: [],
}

export function createTableDefinition(): ComponentDefinition {
  return {
    type: 'table',
    label: 'Table',
    icon: 'table',
    category: 'layout',
    slots: [{ name: 'cell-{r}-{c}', dynamic: true }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      {
        key: 'borderStyle',
        label: 'Border Style',
        type: 'select',
        options: [
          { label: 'None', value: 'none' },
          { label: 'All', value: 'all' },
          { label: 'Horizontal', value: 'horizontal' },
          { label: 'Vertical', value: 'vertical' },
        ],
        defaultValue: 'all',
      },
    ],
    defaultProps: { ...TABLE_DEFAULT_PROPS },
    createInitialSlots: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const rows = (props?.rows as number | undefined) ?? TABLE_DEFAULT_PROPS.rows
      const columns = (props?.columns as number | undefined) ?? TABLE_DEFAULT_PROPS.columns

      const slots: Slot[] = []
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < columns; c++) {
          slots.push({
            id: nanoid() as SlotId,
            nodeId,
            name: cellSlotName(r, c),
            children: [],
          })
        }
      }
      return slots
    },
  }
}
