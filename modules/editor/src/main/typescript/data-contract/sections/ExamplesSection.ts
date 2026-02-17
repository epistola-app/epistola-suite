/**
 * ExamplesSection — Manage test data examples for the data contract.
 *
 * Shows a horizontal chip list for quick overview of all examples with
 * validation status badges. Provides inline editing of example name and data,
 * undo/redo per example, inline field validation, and save/delete controls.
 */

import { html, nothing } from 'lit'
import type { DataContractState } from '../DataContractState.js'
import type { JsonValue } from '../types.js'
import { renderExampleForm } from './ExampleForm.js'

export interface ExamplesUiState {
  editingId: string | null
  isSaving: boolean
  saveSuccess: boolean
  saveError: string | null
  fieldErrorMap: Map<string, string>
  validationErrorCount: number
  exampleErrorCounts: Record<string, number>
  canUndo: boolean
  canRedo: boolean
}

export interface ExamplesSectionCallbacks {
  onSelectExample: (id: string) => void
  onAddExample: () => void
  onDeleteExample: (id: string) => void
  onUpdateExampleName: (id: string, name: string) => void
  onUpdateExampleData: (id: string, path: string, value: JsonValue) => void
  onSaveExample: (id: string) => void
  onUndo: () => void
  onRedo: () => void
}

export function renderExamplesSection(
  state: DataContractState,
  uiState: ExamplesUiState,
  callbacks: ExamplesSectionCallbacks,
): unknown {
  const examples = state.dataExamples
  const selectedExample = uiState.editingId
    ? examples.find((e) => e.id === uiState.editingId)
    : null

  return html`
    <section class="dc-section">
      <h3 class="dc-section-label">Test Data Examples</h3>
      <p class="dc-section-hint">
        Create example data sets to test your templates. Each example must conform to the schema.
      </p>

      <!-- Chip list -->
      <div class="dc-example-chips" role="listbox" aria-label="Examples">
        ${examples.map((ex) => {
          const isActive = uiState.editingId === ex.id
          const errorCount = uiState.exampleErrorCounts[ex.id] ?? 0
          return html`
            <button
              class="dc-example-chip ${isActive ? 'dc-example-chip-active' : ''}"
              role="option"
              aria-selected="${isActive}"
              @click=${() => callbacks.onSelectExample(ex.id)}
            >
              <span class="dc-example-chip-name">${ex.name}</span>
              ${errorCount > 0
                ? html`<span class="dc-example-chip-badge dc-example-chip-badge-error">${errorCount}</span>`
                : html`<span class="dc-example-chip-badge dc-example-chip-badge-ok">&#10003;</span>`
              }
            </button>
          `
        })}
        <button
          class="dc-example-chip dc-example-chip-add"
          @click=${() => callbacks.onAddExample()}
        >+ New</button>
      </div>

      <!-- Example editor -->
      ${selectedExample
        ? html`
            <div class="dc-example-editor">
              <!-- Header: name + delete -->
              <div class="dc-example-header">
                <div class="inspector-field" style="flex:1;min-width:0">
                  <label class="inspector-field-label">Example Name</label>
                  <input
                    type="text"
                    class="ep-input"
                    .value=${selectedExample.name}
                    placeholder="Example name"
                    @change=${(e: Event) => {
                      const name = (e.target as HTMLInputElement).value.trim()
                      if (name) {
                        callbacks.onUpdateExampleName(selectedExample.id, name)
                      }
                    }}
                  />
                </div>
                <button
                  class="dc-example-delete-btn btn btn-sm btn-ghost"
                  @click=${() => callbacks.onDeleteExample(selectedExample.id)}
                  title="Delete this example"
                  aria-label="Delete example"
                >Delete</button>
              </div>

              <!-- Undo/redo toolbar -->
              <div class="dc-example-editor-toolbar">
                <button
                  class="ep-btn-outline btn-sm dc-undo-btn"
                  ?disabled=${!uiState.canUndo}
                  @click=${() => callbacks.onUndo()}
                  title="Undo (Ctrl+Z)"
                  aria-label="Undo"
                >&#8630; Undo</button>
                <button
                  class="ep-btn-outline btn-sm dc-redo-btn"
                  ?disabled=${!uiState.canRedo}
                  @click=${() => callbacks.onRedo()}
                  title="Redo (Ctrl+Shift+Z)"
                  aria-label="Redo"
                >&#8631; Redo</button>

                ${uiState.validationErrorCount > 0
                  ? html`
                      <span class="dc-validation-summary">
                        ${uiState.validationErrorCount} error${uiState.validationErrorCount !== 1 ? 's' : ''}
                      </span>
                    `
                  : nothing
                }
              </div>

              <!-- Form -->
              <div class="dc-example-form-container">
                ${renderExampleForm(
                  state.schema,
                  selectedExample.data,
                  (path, value) => callbacks.onUpdateExampleData(selectedExample.id, path, value),
                  uiState.fieldErrorMap,
                )}
              </div>

              <!-- Save bar -->
              <div class="dc-status-bar">
                ${uiState.saveSuccess
                  ? html`<span class="dc-status-success">Example saved successfully</span>`
                  : nothing
                }
                ${uiState.saveError
                  ? html`<span class="dc-status-error">${uiState.saveError}</span>`
                  : nothing
                }
                <button
                  class="ep-btn-primary btn-sm dc-save-btn"
                  ?disabled=${uiState.isSaving || !state.isExamplesDirty}
                  @click=${() => callbacks.onSaveExample(selectedExample.id)}
                >
                  ${uiState.isSaving ? 'Saving...' : 'Save Example'}
                </button>
              </div>
            </div>
          `
        : examples.length === 0
          ? html`
              <div class="dc-empty-state">
                No test data examples yet. Click "+ New" to create one.
              </div>
            `
          : html`
              <div class="dc-empty-state">
                Select an example above to edit it.
              </div>
            `
      }
    </section>
  `
}
