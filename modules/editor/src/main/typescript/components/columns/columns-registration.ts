// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Columns component definition for the component registry.
 *
 * Exports a factory that creates the ComponentDefinition including
 * renderCanvas and renderInspector hooks — keeping all columns-specific
 * logic out of the generic engine and UI files.
 */

import type { NodeId } from '../../types/index.js';
import type { ComponentDefinition } from '../../engine/registry.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import { html } from 'lit';
import { nanoid } from 'nanoid';
import { styleMap } from 'lit/directives/style-map.js';
import {
  boxStylesToCss,
  renderBoxStyleFields,
  type BoxStyles,
} from '../../ui/inputs/box-style-fields.js';
import { convertSpToPt, DEFAULT_SPACING_UNIT_PT } from '../../ui/style-css.js';

/** Layout style properties available on columns nodes. */
const LAYOUT_STYLES = [
  'padding',
  'margin',
  'backgroundColor',
  'border',
  'borderRadius',
  'keepTogether',
  'keepWithNext',
];

export function createColumnsDefinition(): ComponentDefinition {
  return {
    type: 'columns',
    label: 'Columns',
    icon: 'columns-2',
    category: 'layout',
    slots: [{ name: 'column-{i}', dynamic: true }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [{ key: 'gap', label: 'Gap', type: 'number', defaultValue: 0 }],
    defaultStyles: { marginBottom: '1.5sp' },
    defaultProps: { columnSizes: [1, 1], gap: 0 },
    createInitialSlots: (nodeId: NodeId) => {
      const sizes = [1, 1]; // matches defaultProps.columnSizes
      return sizes.map((_, i) => ({
        id: nanoid(),
        nodeId,
        name: `column-${i}`,
        children: [],
      }));
    },

    examples: [
      {
        name: 'two-equal',
        description: 'A 50/50 split — two equal-width columns, no gap.',
        fragment: {
          rootNodeId: 'n-columns-equal',
          nodes: {
            'n-columns-equal': {
              id: 'n-columns-equal',
              type: 'columns',
              slots: ['s-columns-equal-0', 's-columns-equal-1'],
              props: { columnSizes: [1, 1], gap: 0 },
            },
            'n-columns-equal-left': {
              id: 'n-columns-equal-left',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Left column' }],
                    },
                  ],
                },
              },
            },
            'n-columns-equal-right': {
              id: 'n-columns-equal-right',
              type: 'text',
              slots: [],
              props: {
                content: {
                  type: 'doc',
                  content: [
                    {
                      type: 'paragraph',
                      content: [{ type: 'text', text: 'Right column' }],
                    },
                  ],
                },
              },
            },
          },
          slots: {
            's-columns-equal-0': {
              id: 's-columns-equal-0',
              nodeId: 'n-columns-equal',
              name: 'column-0',
              children: ['n-columns-equal-left'],
            },
            's-columns-equal-1': {
              id: 's-columns-equal-1',
              nodeId: 'n-columns-equal',
              name: 'column-1',
              children: ['n-columns-equal-right'],
            },
          },
        },
      },
      {
        name: 'one-third-two-thirds',
        description:
          'A 1/3 + 2/3 split with a 12pt gap. columnSizes are flex weights, not absolute widths.',
        fragment: {
          rootNodeId: 'n-columns-uneven',
          nodes: {
            'n-columns-uneven': {
              id: 'n-columns-uneven',
              type: 'columns',
              slots: ['s-columns-uneven-0', 's-columns-uneven-1'],
              props: { columnSizes: [1, 2], gap: 12 },
            },
          },
          slots: {
            's-columns-uneven-0': {
              id: 's-columns-uneven-0',
              nodeId: 'n-columns-uneven',
              name: 'column-0',
              children: [],
            },
            's-columns-uneven-1': {
              id: 's-columns-uneven-1',
              nodeId: 'n-columns-uneven',
              name: 'column-1',
              children: [],
            },
          },
        },
      },
    ],

    // ----- Canvas hook -----
    renderCanvas: ({ node, engine: eng, renderSlot }) => {
      const props = node.props ?? {};
      const columnSizes = (props.columnSizes as number[] | undefined) ?? [];
      const columnStyles = (props.columnStyles as BoxStyles[] | undefined) ?? [];
      const gap = (props.gap as number | undefined) ?? 0;
      const gapStyle = gap > 0 ? `${gap}pt` : '0';
      const spacingUnit = (eng as EditorEngine).theme?.spacingUnit ?? DEFAULT_SPACING_UNIT_PT;

      return html`
        <div class="canvas-columns" style="gap: ${gapStyle}">
          ${node.slots.map((slotId, i) => {
            const style = {
              flex: String(columnSizes[i] ?? 1),
              ...boxStylesToCss(columnStyles[i], (v) => convertSpToPt(v, spacingUnit)),
            };
            return html`
              <div class="canvas-column" style=${styleMap(style)}>${renderSlot(slotId)}</div>
            `;
          })}
        </div>
      `;
    },

    // ----- Inspector hook -----
    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;
      const props = node.props ?? {};
      const columnSizes = (props.columnSizes as number[] | undefined) ?? [];
      const gap = (props.gap as number | undefined) ?? 0;
      const count = columnSizes.length;

      const handleAddColumn = () => {
        engine.dispatch({ type: 'AddColumnSlot', nodeId: node.id, size: 1 });
      };

      const handleRemoveColumn = () => {
        engine.dispatch({ type: 'RemoveColumnSlot', nodeId: node.id });
      };

      const handleColumnSizeChange = (index: number, size: number) => {
        const newSizes = [...columnSizes];
        newSizes[index] = Math.max(1, size);
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: { ...props, columnSizes: newSizes },
        });
      };

      // Per-column styles (`columnStyles`, entry i styles column i): an array as long as the
      // columns at most, with `{}` for a column without styles.
      const columnStyles = (props.columnStyles as BoxStyles[] | undefined) ?? [];
      const updateColumnStyles = (index: number, mutate: (styles: BoxStyles) => void) => {
        const next = Array.from({ length: count }, (_, i) => ({ ...columnStyles[i] }));
        mutate(next[index]);
        while (next.length > 0 && Object.keys(next[next.length - 1]).length === 0) next.pop();
        const { columnStyles: _omit, ...rest } = props;
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: next.length > 0 ? { ...rest, columnStyles: next } : rest,
        });
      };

      const handleGapChange = (value: number) => {
        engine.dispatch({
          type: 'UpdateNodeProps',
          nodeId: node.id,
          props: { ...props, gap: value },
        });
      };

      return html`
        <div class="inspector-section">
          <div class="inspector-section-label">Column Layout</div>

          <!-- Column count -->
          <div class="inspector-field">
            <span class="inspector-field-label">Columns</span>
            <div class="inspector-column-count">
              <button
                class="inspector-column-btn"
                ?disabled=${count <= 1}
                @click=${handleRemoveColumn}
              >
                &minus;
              </button>
              <span class="inspector-column-count-value">${count}</span>
              <button
                class="inspector-column-btn"
                ?disabled=${count >= 6}
                @click=${handleAddColumn}
              >
                +
              </button>
            </div>
          </div>

          <!-- Per-column sizes -->
          <div class="inspector-field">
            <span class="inspector-field-label">Column Sizes</span>
            <div class="inspector-column-sizes">
              ${columnSizes.map(
                (size, i) => html`
                  <div class="inspector-column-size">
                    <span class="inspector-column-size-label">${i + 1}</span>
                    <input
                      type="number"
                      class="ep-input inspector-column-size-input"
                      min="1"
                      id=${`columns-size-${i}`}
                      .value=${String(size)}
                      @change=${(e: Event) =>
                        handleColumnSizeChange(i, Number((e.target as HTMLInputElement).value))}
                    />
                  </div>
                `,
              )}
            </div>
          </div>

          <!-- Gap -->
          <div class="inspector-field">
            <label class="inspector-field-label" for="columns-gap">Gap (pt)</label>
            <input
              type="number"
              class="ep-input"
              min="0"
              id="columns-gap"
              .value=${String(gap)}
              @change=${(e: Event) => handleGapChange(Number((e.target as HTMLInputElement).value))}
            />
          </div>
        </div>

        <div class="inspector-section">
          <div class="inspector-section-label">Column Styles</div>
          ${Array.from(
            { length: count },
            (_, i) => html`
              <details class="inspector-column-styles">
                <summary>Column ${i + 1}</summary>
                ${renderBoxStyleFields(
                  columnStyles[i] ?? {},
                  (mutate) => updateColumnStyles(i, mutate),
                  `columns-${node.id}-${i}`,
                )}
              </details>
            `,
          )}
        </div>
      `;
    },
  };
}
