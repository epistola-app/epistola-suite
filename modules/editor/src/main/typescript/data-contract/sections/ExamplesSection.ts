/**
 * ExamplesSection — Manage test data examples for the data contract.
 *
 * Shows a horizontal chip list for quick overview of all examples with
 * validation status badges. Provides inline editing of example name and data,
 * undo/redo per example, inline field validation, and save/delete controls.
 */

import { html } from 'lit';
import type { DataExample, JsonSchema, JsonValue } from '../types.js';
import { renderExampleForm } from './ExampleForm.js';

export interface ExamplesSectionState {
  dataExamples: DataExample[];
  schema: JsonSchema | null;
}

export interface ExamplesUiState {
  editingId: string | null;
  fieldErrorMap: Map<string, string>;
  validationErrorCount: number;
  exampleErrorCounts: Record<string, number>;
  canUndo: boolean;
  canRedo: boolean;
}

export interface ExamplesSectionCallbacks {
  onSelectExample: (id: string) => void;
  onAddExample: () => void;
  onDeleteExample: (id: string) => void;
  onUpdateExampleName: (id: string, name: string) => void;
  onUpdateExampleData: (id: string, path: string, value: JsonValue) => void;
  onClearExampleData: (id: string, path: string) => void;
  onUndo: () => void;
  onRedo: () => void;
}

export function renderExamplesSection(
  state: ExamplesSectionState,
  uiState: ExamplesUiState,
  callbacks: ExamplesSectionCallbacks,
): unknown {
  const examples = state.dataExamples;
  const selectedExample = uiState.editingId
    ? examples.find((e) => e.id === uiState.editingId)
    : null;

  return html`
    <section class="dc-section dc-examples-section">
      <h3 class="dc-section-label">Test Data Examples</h3>
      <p class="dc-section-hint">
        Create example data sets to test your templates. Each example must conform to the schema.
      </p>

      <!-- Chip list -->
      <div class="dc-example-chips" role="listbox" aria-label="Examples">
        ${examples.map((ex) => {
          const isActive = uiState.editingId === ex.id;
          const errorCount = uiState.exampleErrorCounts[ex.id] ?? 0;
          return html`
            <button
              class="dc-example-chip ${isActive ? 'dc-example-chip-active' : ''}"
              role="option"
              aria-selected="${isActive}"
              aria-label="${ex.name}${errorCount > 0
                ? `, ${errorCount} error${errorCount !== 1 ? 's' : ''}`
                : ', valid'}"
              @click=${() => callbacks.onSelectExample(ex.id)}
              title="${ex.name}"
            >
              <span class="dc-example-chip-name">${ex.name}</span>
              ${errorCount > 0
                ? html`<span
                    class="dc-example-chip-badge dc-example-chip-badge-error"
                    aria-hidden="true"
                    >${errorCount}</span
                  >`
                : html`<span
                    class="dc-example-chip-badge dc-example-chip-badge-ok"
                    aria-hidden="true"
                    >&#10003;</span
                  >`}
            </button>
          `;
        })}
        <button
          class="dc-example-chip dc-example-chip-add"
          @click=${() => callbacks.onAddExample()}
        >
          + New
        </button>
      </div>

      <!-- Example editor card -->
      ${selectedExample
        ? html`
            <div class="dc-example-card">
              <!-- Card Header -->
              <div class="dc-example-card-header">
                <div class="dc-example-card-header-main">
                  <label class="dc-example-field-label" for="example-name-input"
                    >Example Name</label
                  >
                  <input
                    type="text"
                    id="example-name-input"
                    class="ep-input dc-example-name-input"
                    .value=${selectedExample.name}
                    placeholder="Enter example name"
                    @change=${(e: Event) => {
                      const name = (e.target as HTMLInputElement).value.trim();
                      if (name) {
                        callbacks.onUpdateExampleName(selectedExample.id, name);
                      }
                    }}
                  />
                </div>
                <button
                  class="dc-example-delete-btn"
                  @click=${() => callbacks.onDeleteExample(selectedExample.id)}
                  title="Delete this example"
                  aria-label="Delete example"
                >
                  <svg
                    width="16"
                    height="16"
                    viewBox="0 0 16 16"
                    fill="none"
                    xmlns="http://www.w3.org/2000/svg"
                    aria-hidden="true"
                  >
                    <path
                      d="M2 4h12M5.333 4V2.667a1.333 1.333 0 011.334-1.334h2.666a1.333 1.333 0 011.334 1.334V4m2 0v9.333a1.333 1.333 0 01-1.334 1.334H4.667a1.333 1.333 0 01-1.334-1.334V4H12z"
                      stroke="currentColor"
                      stroke-width="1.5"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </button>
              </div>

              <!-- Floating Toolbar -->
              <div class="dc-example-toolbar">
                <div class="dc-example-toolbar-actions">
                  <button
                    class="dc-example-toolbar-btn"
                    ?disabled=${!uiState.canUndo}
                    @click=${() => callbacks.onUndo()}
                    title="Undo (Ctrl+Z)"
                    aria-label="Undo"
                  >
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path
                        d="M3 6h7a4 4 0 014 4v0M3 6l3-3M3 6l3 3"
                        stroke="currentColor"
                        stroke-width="1.5"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                    Undo
                  </button>
                  <button
                    class="dc-example-toolbar-btn"
                    ?disabled=${!uiState.canRedo}
                    @click=${() => callbacks.onRedo()}
                    title="Redo (Ctrl+Shift+Z)"
                    aria-label="Redo"
                  >
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                      <path
                        d="M13 6H6a4 4 0 00-4 4v0M13 6l-3-3M13 6l-3 3"
                        stroke="currentColor"
                        stroke-width="1.5"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                    Redo
                  </button>
                </div>

                ${uiState.validationErrorCount > 0
                  ? html`
                      <span class="dc-validation-summary">
                        <svg
                          width="14"
                          height="14"
                          viewBox="0 0 16 16"
                          fill="none"
                          aria-hidden="true"
                        >
                          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.5" />
                          <path
                            d="M8 5v4M8 11v.01"
                            stroke="currentColor"
                            stroke-width="1.5"
                            stroke-linecap="round"
                          />
                        </svg>
                        ${uiState.validationErrorCount}
                        error${uiState.validationErrorCount !== 1 ? 's' : ''}
                      </span>
                    `
                  : html`
                      <span class="dc-validation-success">
                        <svg
                          width="14"
                          height="14"
                          viewBox="0 0 16 16"
                          fill="none"
                          aria-hidden="true"
                        >
                          <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.5" />
                          <path
                            d="M5 8l2 2 4-4"
                            stroke="currentColor"
                            stroke-width="1.5"
                            stroke-linecap="round"
                            stroke-linejoin="round"
                          />
                        </svg>
                        Valid
                      </span>
                    `}
              </div>

              <!-- Form -->
              <div class="dc-example-form-container">
                ${renderExampleForm(
                  state.schema,
                  selectedExample.data,
                  (path, value) => callbacks.onUpdateExampleData(selectedExample.id, path, value),
                  (path) => callbacks.onClearExampleData(selectedExample.id, path),
                  uiState.fieldErrorMap,
                )}
              </div>
            </div>
          `
        : examples.length === 0
          ? html`
              <div class="dc-empty-state">
                <div class="dc-empty-state-icon">
                  <svg
                    width="32"
                    height="32"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.5"
                    aria-hidden="true"
                  >
                    <path
                      d="M9 12h.01M15 12h.01M10 16c.5.3 1.2.5 2 .5s1.5-.2 2-.5M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10z"
                    />
                  </svg>
                </div>
                <p>No test data examples yet</p>
                <span class="dc-empty-state-hint">Click "+ New" to create your first example</span>
              </div>
            `
          : html`
              <div class="dc-empty-state">
                <div class="dc-empty-state-icon dc-empty-state-icon-muted">
                  <svg
                    width="32"
                    height="32"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.5"
                    aria-hidden="true"
                  >
                    <path
                      d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
                    />
                  </svg>
                </div>
                <p>Select an example to edit</p>
                <span class="dc-empty-state-hint">Choose one from the chips above</span>
              </div>
            `}
    </section>
  `;
}
