/**
 * SchemaSection — Visual schema builder with field management.
 *
 * Renders a list of schema fields with add/delete/update controls,
 * undo/redo, and validation warnings.
 * Accepts VisualSchema directly — no conversion in the render path.
 */

import { html } from 'lit'
import type { VisualSchema } from '../types.js'
import type { SchemaCommand } from '../utils/schemaCommands.js'
import { renderSchemaFieldRow } from './SchemaFieldRow.js'
import { renderValidationMessages } from './ValidationMessages.js'

export interface SchemaUiState {
  warnings: Array<{ path: string; message: string }>
  canUndo: boolean
  canRedo: boolean
}

export interface SchemaSectionCallbacks {
  onCommand: (command: SchemaCommand) => void
  onToggleFieldExpand: (fieldId: string) => void
  onUndo: () => void
  onRedo: () => void
}

export function renderSchemaSection(
  visualSchema: VisualSchema,
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
  expandedFields: Set<string>,
): unknown {
  const fields = visualSchema.fields
  const hasFields = fields.length > 0

  return html`
    <section class="dc-section">
      <h3 class="dc-section-label">Schema Definition</h3>
      <p class="dc-section-hint">
        Define the data fields that templates can use. Each field becomes a variable available in template expressions.
      </p>

      <!-- Toolbar -->
      <div class="dc-toolbar">
        <div class="dc-toolbar-spacer"></div>

        <button
          class="ep-btn-outline btn-sm dc-undo-btn"
          @click=${() => callbacks.onUndo()}
          ?disabled=${!uiState.canUndo}
          title="Undo (Ctrl+Z)"
          aria-label="Undo"
        >Undo</button>

        <button
          class="ep-btn-outline btn-sm dc-redo-btn"
          @click=${() => callbacks.onRedo()}
          ?disabled=${!uiState.canRedo}
          title="Redo (Ctrl+Shift+Z)"
          aria-label="Redo"
        >Redo</button>
      </div>

      <!-- Validation warnings -->
      ${renderValidationMessages(uiState.warnings)}

      <!-- Field list -->
      ${hasFields
        ? html`
            <div class="dc-field-list">
              <div class="dc-field-list-header">
                <span></span>
                <span>Name</span>
                <span>Type</span>
                <span>Items</span>
                <span>Req</span>
                <span></span>
                <span></span>
              </div>
              ${fields.map((field) =>
                renderSchemaFieldRow(
                  field,
                  callbacks.onCommand,
                  0,
                  expandedFields,
                  callbacks.onToggleFieldExpand,
                ),
              )}
            </div>
          `
        : html`
            <div class="dc-empty-state">
              No fields defined yet. Add a field below.
            </div>
          `
      }

      <!-- Add field button -->
      <button
        class="ep-btn-outline btn-sm dc-add-field-btn"
        @click=${() => callbacks.onCommand({ type: 'addField', parentFieldId: null })}
      >+ Add Field</button>
    </section>
  `
}
