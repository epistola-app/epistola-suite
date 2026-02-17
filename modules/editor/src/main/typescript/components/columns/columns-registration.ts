/**
 * Columns component definition for the component registry.
 *
 * Exports a factory that creates the ComponentDefinition including
 * renderCanvas and renderInspector hooks — keeping all columns-specific
 * logic out of the generic engine and UI files.
 */

import type { NodeId, SlotId } from '../../types/index.js'
import type { ComponentDefinition } from '../../engine/registry.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import { html } from 'lit'
import { nanoid } from 'nanoid'

/** Layout style properties available on columns nodes. */
const LAYOUT_STYLES = [
  'padding', 'margin',
  'backgroundColor',
  'borderWidth', 'borderStyle', 'borderColor', 'borderRadius',
]

export function createColumnsDefinition(): ComponentDefinition {
  return {
    type: 'columns',
    label: 'Columns',
    icon: 'columns-2',
    category: 'layout',
    slots: [{ name: 'column-{i}', dynamic: true }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'gap', label: 'Gap', type: 'number', defaultValue: 0 },
    ],
    defaultProps: { columnSizes: [1, 1], gap: 0 },
    createInitialSlots: (nodeId: NodeId) => {
      const sizes = [1, 1] // matches defaultProps.columnSizes
      return sizes.map((_, i) => ({
        id: nanoid() as SlotId,
        nodeId,
        name: `column-${i}`,
        children: [],
      }))
    },

    // ----- Canvas hook -----
    renderCanvas: ({ node, renderSlot }) => {
      const props = node.props ?? {}
      const columnSizes = (props.columnSizes as number[] | undefined) ?? []
      const gap = (props.gap as number | undefined) ?? 0
      const gapStyle = gap > 0 ? `${gap}pt` : '0'

      return html`
        <div class="canvas-columns" style="gap: ${gapStyle}">
          ${node.slots.map((slotId, i) => {
            const flex = columnSizes[i] ?? 1
            return html`
              <div class="canvas-column" style="flex: ${flex}">
                ${renderSlot(slotId)}
              </div>
            `
          })}
        </div>
      `
    },

    // ----- Inspector hook -----
    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine
      const props = node.props ?? {}
      const columnSizes = (props.columnSizes as number[] | undefined) ?? []
      const gap = (props.gap as number | undefined) ?? 0
      const count = columnSizes.length

      const handleAddColumn = () => {
        engine.dispatch({ type: 'AddColumnSlot', nodeId: node.id, size: 1 })
      }

      const handleRemoveColumn = () => {
        engine.dispatch({ type: 'RemoveColumnSlot', nodeId: node.id })
      }

      const handleColumnSizeChange = (index: number, size: number) => {
        const newSizes = [...columnSizes]
        newSizes[index] = Math.max(1, size)
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: { ...props, columnSizes: newSizes },
        })
      }

      const handleGapChange = (value: number) => {
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: { ...props, gap: value },
        })
      }

      return html`
        <div class="inspector-section">
          <div class="inspector-section-label">Column Layout</div>

          <!-- Column count -->
          <div class="inspector-field">
            <label class="inspector-field-label">Columns</label>
            <div class="inspector-column-count">
              <button
                class="inspector-column-btn"
                ?disabled=${count <= 1}
                @click=${handleRemoveColumn}
              >&minus;</button>
              <span class="inspector-column-count-value">${count}</span>
              <button
                class="inspector-column-btn"
                ?disabled=${count >= 6}
                @click=${handleAddColumn}
              >+</button>
            </div>
          </div>

          <!-- Per-column sizes -->
          <div class="inspector-field">
            <label class="inspector-field-label">Column Sizes</label>
            <div class="inspector-column-sizes">
              ${columnSizes.map((size, i) => html`
                <div class="inspector-column-size">
                  <span class="inspector-column-size-label">${i + 1}</span>
                  <input
                    type="number"
                    class="ep-input inspector-column-size-input"
                    min="1"
                    .value=${String(size)}
                    @change=${(e: Event) => handleColumnSizeChange(i, Number((e.target as HTMLInputElement).value))}
                  />
                </div>
              `)}
            </div>
          </div>

          <!-- Gap -->
          <div class="inspector-field">
            <label class="inspector-field-label">Gap (pt)</label>
            <input
              type="number"
              class="ep-input"
              min="0"
              .value=${String(gap)}
              @change=${(e: Event) => handleGapChange(Number((e.target as HTMLInputElement).value))}
            />
          </div>
        </div>
      `
    },
  }
}
