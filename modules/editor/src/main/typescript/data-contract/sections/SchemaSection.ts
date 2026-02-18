/**
 * SchemaSection — Visual schema builder with field management.
 *
 * Renders a list of schema fields with add/delete/update controls,
 * a "Generate from example" tool, undo/redo, and save state indicators.
 * Accepts VisualSchema directly — no conversion in the render path.
 */

import { html, nothing } from 'lit'
import type { DataContractState } from '../DataContractState.js'
import type { VisualSchema } from '../types.js'
import type { SchemaCommand } from '../utils/schemaCommands.js'
import { renderSchemaFieldRow } from './SchemaFieldRow.js'
import { renderValidationMessages } from './ValidationMessages.js'
import type { MigrationSuggestion } from '../utils/schemaMigration.js'

export interface SchemaUiState {
  warnings: Array<{ path: string; message: string }>
  showConfirmGenerate: boolean
  showMigrationAssistant: boolean
  pendingMigrations: MigrationSuggestion[]
  canUndo: boolean
  canRedo: boolean
}

export interface SchemaSectionCallbacks {
  onCommand: (command: SchemaCommand) => void
  onGenerateFromExample: () => void
  onConfirmGenerate: () => void
  onCancelGenerate: () => void
  onToggleFieldExpand: (fieldId: string) => void
  onUndo: () => void
  onRedo: () => void
}

export function renderSchemaSection(
  visualSchema: VisualSchema,
  state: DataContractState,
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
        <button
          class="ep-btn-outline btn-sm"
          @click=${() => callbacks.onGenerateFromExample()}
          ?disabled=${state.dataExamples.length === 0}
          title="${state.dataExamples.length === 0 ? 'Add a test data example first' : 'Infer schema from first example'}"
        >Generate from example</button>

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

      <!-- Generate confirmation dialog -->
      ${uiState.showConfirmGenerate
        ? html`
            <div class="dc-confirm-bar alert alert-warning">
              <div>
                <strong>Replace existing schema?</strong>
                This will overwrite all current field definitions with fields inferred from the first example.
              </div>
              <div class="dc-confirm-actions">
                <button class="btn btn-sm btn-destructive" @click=${() => callbacks.onConfirmGenerate()}>
                  Replace
                </button>
                <button class="btn btn-sm btn-ghost" @click=${() => callbacks.onCancelGenerate()}>
                  Cancel
                </button>
              </div>
            </div>
          `
        : nothing
      }

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
              No fields defined yet. Add a field below or generate from an example.
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
