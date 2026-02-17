/**
 * ExamplesSection — Manage test data examples for the data contract.
 *
 * Provides a selector for switching between examples, inline editing
 * of example name and data, validation against the current schema,
 * and save/delete controls.
 */

import { html, nothing } from 'lit'
import type { DataContractState } from '../DataContractState.js'
import type { JsonValue } from '../types.js'
import { renderExampleForm } from './ExampleForm.js'
import { renderValidationMessages, type ValidationWarning } from './ValidationMessages.js'

export interface ExamplesUiState {
  editingId: string | null
  isSaving: boolean
  saveSuccess: boolean
  saveError: string | null
  validationWarnings: ValidationWarning[]
}

export interface ExamplesSectionCallbacks {
  onSelectExample: (id: string) => void
  onAddExample: () => void
  onDeleteExample: (id: string) => void
  onUpdateExampleName: (id: string, name: string) => void
  onUpdateExampleData: (id: string, path: string, value: JsonValue) => void
  onSaveExample: (id: string) => void
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

      <!-- Example selector toolbar -->
      <div class="dc-example-toolbar">
        <select
          class="ep-select dc-example-select"
          .value=${uiState.editingId ?? ''}
          aria-label="Select example"
          @change=${(e: Event) => {
            const value = (e.target as HTMLSelectElement).value
            if (value) {
              callbacks.onSelectExample(value)
            }
          }}
        >
          <option value="" ?selected=${!uiState.editingId}>
            ${examples.length === 0 ? 'No examples' : 'Select an example...'}
          </option>
          ${examples.map(
            (ex) => html`
              <option value=${ex.id} ?selected=${uiState.editingId === ex.id}>
                ${ex.name}
              </option>
            `,
          )}
        </select>

        <button
          class="ep-btn-outline btn-sm"
          @click=${() => callbacks.onAddExample()}
        >+ New</button>

        ${selectedExample
          ? html`
              <button
                class="dc-example-delete-btn btn btn-sm btn-ghost"
                @click=${() => callbacks.onDeleteExample(selectedExample.id)}
                title="Delete this example"
                aria-label="Delete example"
              >Delete</button>
            `
          : nothing
        }
      </div>

      <!-- Example editor -->
      ${selectedExample
        ? html`
            <div class="dc-example-editor">
              <!-- Example name -->
              <div class="inspector-field">
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

              <!-- Validation warnings -->
              ${renderValidationMessages(uiState.validationWarnings)}

              <!-- Form -->
              <div class="dc-example-form-container">
                ${renderExampleForm(
                  state.schema,
                  selectedExample.data,
                  (path, value) => callbacks.onUpdateExampleData(selectedExample.id, path, value),
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
