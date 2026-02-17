/**
 * SchemaSection — Visual schema builder with field management.
 *
 * Renders a list of schema fields with add/delete/update controls,
 * a "Generate from example" tool, and save state indicators.
 * Converts between visual fields and JSON Schema for persistence.
 */

import { html, nothing } from 'lit'
import type { DataContractState } from '../DataContractState.js'
import type { SchemaFieldUpdate, VisualSchema } from '../types.js'
import { jsonSchemaToVisualSchema } from '../utils/schemaUtils.js'
import { renderSchemaFieldRow } from './SchemaFieldRow.js'
import { renderValidationMessages } from './ValidationMessages.js'
import type { MigrationSuggestion } from '../utils/schemaMigration.js'

export interface SchemaUiState {
  isSaving: boolean
  saveSuccess: boolean
  saveError: string | null
  warnings: Array<{ path: string; message: string }>
  showConfirmGenerate: boolean
  showMigrationAssistant: boolean
  pendingMigrations: MigrationSuggestion[]
}

export interface SchemaSectionCallbacks {
  onFieldUpdate: (fieldId: string, updates: SchemaFieldUpdate) => void
  onFieldDelete: (fieldId: string) => void
  onAddField: () => void
  onSave: () => void
  onGenerateFromExample: () => void
  onConfirmGenerate: () => void
  onCancelGenerate: () => void
  onToggleFieldExpand: (fieldId: string) => void
}

export function renderSchemaSection(
  state: DataContractState,
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
  expandedFields: Set<string>,
): unknown {
  const visual: VisualSchema = jsonSchemaToVisualSchema(state.schema)
  const fields = visual.fields
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
                <span class="dc-field-list-header-spacer"></span>
                <span class="dc-field-list-header-cell dc-field-list-header-name">Name</span>
                <span class="dc-field-list-header-cell dc-field-list-header-type">Type</span>
                <span class="dc-field-list-header-cell dc-field-list-header-req">Req</span>
                <span class="dc-field-list-header-spacer"></span>
              </div>
              ${fields.map((field) =>
                renderSchemaFieldRow(
                  field,
                  {
                    onUpdate: callbacks.onFieldUpdate,
                    onDelete: callbacks.onFieldDelete,
                  },
                  false,
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
        @click=${() => callbacks.onAddField()}
      >+ Add Field</button>

      <!-- Status bar -->
      <div class="dc-status-bar">
        ${uiState.saveSuccess
          ? html`<span class="dc-status-success">Schema saved successfully</span>`
          : nothing
        }
        ${uiState.saveError
          ? html`<span class="dc-status-error">${uiState.saveError}</span>`
          : nothing
        }
        <button
          class="ep-btn-primary btn-sm dc-save-btn"
          ?disabled=${uiState.isSaving || !state.isSchemaDirty}
          @click=${() => callbacks.onSave()}
        >
          ${uiState.isSaving ? 'Saving...' : 'Save Schema'}
        </button>
      </div>
    </section>
  `
}
