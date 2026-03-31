/**
 * EpistolaDataContractEditor — Root Lit element for the data contract editor.
 *
 * Two tabs: "Schema" and "Test Data".
 * Owns a DataContractState instance, manages all UI state, delegates
 * rendering to section render functions.
 * Light DOM (no Shadow DOM) for design system CSS integration.
 *
 * Schema mutations happen through SchemaCommands, with VisualSchema as the
 * primary editing state. JSON Schema conversion only happens on load and save.
 * A snapshot-based undo/redo stack tracks history.
 *
 * Example edits also have per-example undo/redo via SnapshotHistory<JsonObject>.
 * All examples are validated against the schema, with inline field errors and
 * chip-level badges.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { nanoid } from 'nanoid';
import { icon } from '../ui/icons.js';
import { DataContractState } from './DataContractState.js';
import type {
  DataExample,
  JsonObject,
  JsonSchema,
  JsonValue,
  SaveCallbacks,
  SchemaField,
  VisualSchema,
} from './types.js';
import { jsonSchemaToVisualSchema, visualSchemaToJsonSchema } from './utils/schemaUtils.js';
import type { SchemaCommand } from './utils/schemaCommands.js';
import { SchemaCommandHistory } from './utils/schemaCommandHistory.js';
import { SnapshotHistory } from './utils/snapshotHistory.js';
import {
  detectMigrations,
  applyAllMigrations,
  type MigrationSuggestion,
} from './utils/schemaMigration.js';
import { validateDataAgainstSchema, type SchemaValidationError } from './utils/schemaValidation.js';
import { checkSchemaCompatibility, type CompatibilityIssue } from './utils/schemaCompatibility.js';
import {
  renderSchemaSection,
  type SchemaUiState,
  type SchemaSectionCallbacks,
} from './sections/SchemaSection.js';
import {
  renderExamplesSection,
  type ExamplesUiState,
  type ExamplesSectionCallbacks,
} from './sections/ExamplesSection.js';
import { renderMigrationDialog, migrationKey } from './sections/MigrationAssistant.js';
import { renderJsonSchemaView } from './sections/JsonSchemaView.js';
import { renderImportSchemaDialog } from './sections/ImportSchemaDialog.js';
import { setNestedValue, buildFieldErrorMap } from './sections/ExampleForm.js';

type TabId = 'schema' | 'examples';

@customElement('epistola-data-contract-editor')
export class EpistolaDataContractEditor extends LitElement {
  override createRenderRoot() {
    return this;
  }

  // ---------------------------------------------------------------------------
  // External state (injected via init())
  // ---------------------------------------------------------------------------

  contractState?: DataContractState;

  // ---------------------------------------------------------------------------
  // Schema editing state (VisualSchema is the primary editing state)
  // ---------------------------------------------------------------------------

  @state() private _visualSchema: VisualSchema = { fields: [] };
  private _commandHistory = new SchemaCommandHistory();

  // ---------------------------------------------------------------------------
  // UI state (reactive via @state())
  // ---------------------------------------------------------------------------

  @state() private _activeTab: TabId = 'schema';

  // Schema tab UI state
  @state() private _schemaWarnings: Array<{ path: string; message: string }> = [];
  @state() private _expandedFields = new Set<string>();
  @state() private _selectedFieldId: string | null = null;
  @state() private _schemaViewMode: 'visual' | 'json' = 'visual';
  @state() private _compatibilityIssues: CompatibilityIssue[] = [];

  // Import dialog state
  @state() private _showImportDialog = false;
  @state() private _importParseError: string | null = null;

  // Copy feedback
  @state() private _copySuccess = false;

  // Examples tab UI state
  @state() private _editingExampleId: string | null = null;

  // Unified save state
  @state() private _saving = false;
  @state() private _saveSuccess = false;
  @state() private _saveError: string | null = null;
  @state() private _canForceSave = false;

  // Per-example undo/redo stacks
  private _exampleHistories = new Map<string, SnapshotHistory<JsonObject>>();
  @state() private _exampleCanUndo = false;
  @state() private _exampleCanRedo = false;

  // Validation errors for all examples (keyed by example ID)
  @state() private _exampleValidationErrors = new Map<string, SchemaValidationError[]>();

  // Migration dialog state
  @state() private _showMigrationDialog = false;
  @state() private _pendingMigrations: MigrationSuggestion[] = [];
  @state() private _selectedMigrations = new Set<string>();

  // Timers
  private _successTimer?: ReturnType<typeof setTimeout>;

  // Event listener refs
  private _boundBeforeUnload = this._handleBeforeUnload.bind(this);
  private _boundKeyDown = this._handleKeyDown.bind(this);

  // ---------------------------------------------------------------------------
  // Initialization
  // ---------------------------------------------------------------------------

  init(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ): void {
    this.contractState = new DataContractState(initialSchema, initialExamples, callbacks);

    this.contractState.addEventListener('change', () => {
      this.requestUpdate();
    });

    // Check compatibility and set editing mode
    if (initialSchema) {
      const compat = checkSchemaCompatibility(initialSchema);
      this._compatibilityIssues = compat.issues;
      if (!compat.compatible) {
        this.contractState.setRawJsonSchema(initialSchema, 'json-only');
        this._schemaViewMode = 'json';
      }
    }

    // Convert initial JSON Schema to VisualSchema once — this is now the primary editing state
    this._visualSchema = jsonSchemaToVisualSchema(initialSchema);
    this._commandHistory.clear();

    // Pre-select first field if available
    if (this._visualSchema.fields.length > 0) {
      this._selectedFieldId = this._visualSchema.fields[0].id;
    }

    // Pre-select first example if available
    if (initialExamples.length > 0) {
      this._editingExampleId = initialExamples[0].id;
    }

    // Validate all examples on init
    this._validateAllExamples();
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override connectedCallback() {
    super.connectedCallback();
    window.addEventListener('beforeunload', this._boundBeforeUnload);
    window.addEventListener('keydown', this._boundKeyDown);
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('beforeunload', this._boundBeforeUnload);
    window.removeEventListener('keydown', this._boundKeyDown);
    if (this._successTimer) clearTimeout(this._successTimer);
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    if (!this.contractState) {
      return html`<div class="dc-empty-state">No data contract loaded.</div>`;
    }

    const state = this.contractState!;

    return html`
      <div class="dc-editor-layout">
        <!-- Tab bar -->
        <div class="dc-tabs" role="tablist">
          ${this._renderTab('schema', 'Schema')} ${this._renderTab('examples', 'Test Data')}

          <div class="dc-tabs-spacer"></div>

          <!-- Unified save -->
          ${this._saveSuccess
            ? html`<span class="dc-status-success">Saved successfully</span>`
            : nothing}
          ${this._saveError
            ? html`<span class="dc-status-error">${this._saveError}</span>`
            : nothing}
          ${this._canForceSave
            ? html`<button
                class="ep-btn-outline btn-sm dc-force-save-btn"
                ?disabled=${this._saving}
                @click=${() => this._executeForceSave()}
              >
                Save Anyway
              </button>`
            : nothing}
          <button
            class="ep-btn-primary btn-sm dc-save-btn"
            ?disabled=${this._saving || !state.isDirty}
            @click=${() => this._saveAll()}
          >
            ${this._saving ? 'Saving...' : 'Save'}
          </button>
        </div>

        <!-- Tab content -->
        <div class="dc-tab-content">
          ${this._activeTab === 'schema' ? this._renderSchemaTab() : this._renderExamplesTab()}
        </div>
      </div>

      <!-- Migration dialog -->
      ${this._showMigrationDialog
        ? html`
            <dialog class="dc-dialog" open @close=${() => this._closeMigrationDialog()}>
              ${renderMigrationDialog(this._pendingMigrations, this._selectedMigrations, {
                onApply: (selected) => this._applyMigrations(selected),
                onForceSave: () => this._forceSave(),
                onCancel: () => this._closeMigrationDialog(),
                onToggleMigration: (m) => this._toggleMigration(m),
                onSelectAll: () => this._selectAllMigrations(),
                onSelectNone: () => this._selectNoneMigrations(),
              })}
            </dialog>
          `
        : nothing}

      <!-- Import schema dialog -->
      ${this._showImportDialog
        ? html`
            <dialog class="dc-dialog" open @close=${() => this._closeImportDialog()}>
              ${renderImportSchemaDialog(this._importParseError, {
                onImportFromText: (text) => this._handleImportFromText(text),
                onImportFromFile: (file) => this._handleImportFromFile(file),
                onCancel: () => this._closeImportDialog(),
              })}
            </dialog>
          `
        : nothing}
    `;
  }

  private _renderTab(tabId: TabId, label: string): unknown {
    const isActive = this._activeTab === tabId;
    return html`
      <button
        class="dc-tab ${isActive ? 'dc-tab-active' : ''}"
        role="tab"
        aria-selected="${isActive}"
        @click=${() => {
          this._activeTab = tabId;
        }}
      >
        ${label}
      </button>
    `;
  }

  // ---------------------------------------------------------------------------
  // Schema tab
  // ---------------------------------------------------------------------------

  private _renderSchemaTab(): unknown {
    const state = this.contractState!;
    const isJsonOnly = state.schemaEditMode === 'json-only';

    const jsonSchemaViewCallbacks = {
      onCopyToClipboard: () => this._copyJsonSchemaToClipboard(),
      onImportSchema: () => this._openImportDialog(),
    };

    // JSON-only mode: no visual editor, just JSON view
    if (isJsonOnly) {
      return renderJsonSchemaView(
        state.rawJsonSchema,
        this._compatibilityIssues,
        this._copySuccess,
        jsonSchemaViewCallbacks,
      );
    }

    // Visual mode: show Visual/JSON sub-tab toggle
    return html`
      <!-- View toggle -->
      <div class="dc-toolbar">
        <div class="dc-schema-view-toggle">
          <button
            class="dc-schema-view-toggle-btn ${this._schemaViewMode === 'visual'
              ? 'dc-schema-view-toggle-btn-active'
              : ''}"
            @click=${() => {
              this._schemaViewMode = 'visual';
            }}
          >
            Visual
          </button>
          <button
            class="dc-schema-view-toggle-btn ${this._schemaViewMode === 'json'
              ? 'dc-schema-view-toggle-btn-active'
              : ''}"
            @click=${() => {
              this._schemaViewMode = 'json';
            }}
          >
            JSON
          </button>
        </div>

        <button
          class="ep-btn-outline btn-sm dc-btn-icon"
          @click=${() => this._openImportDialog()}
          title="Import a JSON Schema"
        >
          ${icon('upload', 14)} Import
        </button>
      </div>

      ${this._schemaViewMode === 'json'
        ? renderJsonSchemaView(
            this._visualSchema.fields.length > 0
              ? visualSchemaToJsonSchema(this._visualSchema)
              : null,
            [],
            this._copySuccess,
            jsonSchemaViewCallbacks,
          )
        : this._renderVisualSchemaSection()}
    `;
  }

  private _renderVisualSchemaSection(): unknown {
    const uiState: SchemaUiState = {
      warnings: this._schemaWarnings,
      canUndo: this._commandHistory.canUndo,
      canRedo: this._commandHistory.canRedo,
      selectedFieldId: this._selectedFieldId,
    };

    const callbacks: SchemaSectionCallbacks = {
      onCommand: (command) => this._executeCommand(command),
      onToggleFieldExpand: (fieldId) => this._toggleFieldExpand(fieldId),
      onSelectField: (fieldId) => this._selectField(fieldId),
      onUndo: () => this._undo(),
      onRedo: () => this._redo(),
    };

    return renderSchemaSection(this._visualSchema, uiState, callbacks, this._expandedFields);
  }

  // ---------------------------------------------------------------------------
  // Examples tab
  // ---------------------------------------------------------------------------

  private _renderExamplesTab(): unknown {
    const state = this.contractState!;

    // Derive errors for selected example
    const errorsForSelected = this._editingExampleId
      ? (this._exampleValidationErrors.get(this._editingExampleId) ?? [])
      : [];
    const fieldErrorMap = buildFieldErrorMap(errorsForSelected);

    // Derive error counts per example for chip badges
    const exampleErrorCounts: Record<string, number> = {};
    for (const ex of state.dataExamples) {
      exampleErrorCounts[ex.id] = (this._exampleValidationErrors.get(ex.id) ?? []).length;
    }

    const uiState: ExamplesUiState = {
      editingId: this._editingExampleId,
      fieldErrorMap,
      validationErrorCount: errorsForSelected.length,
      exampleErrorCounts,
      canUndo: this._exampleCanUndo,
      canRedo: this._exampleCanRedo,
    };

    const callbacks: ExamplesSectionCallbacks = {
      onSelectExample: (id) => this._selectExample(id),
      onAddExample: () => this._addExample(),
      onDeleteExample: (id) => this._deleteExample(id),
      onUpdateExampleName: (id, name) => this._updateExampleName(id, name),
      onUpdateExampleData: (id, path, value) => this._updateExampleData(id, path, value),
      onUndo: () => this._undoExampleData(),
      onRedo: () => this._redoExampleData(),
    };

    return renderExamplesSection(state, uiState, callbacks);
  }

  // ---------------------------------------------------------------------------
  // Schema operations (command-based)
  // ---------------------------------------------------------------------------

  private _executeCommand(command: SchemaCommand): void {
    const prevSchema = this._visualSchema;
    this._visualSchema = this._commandHistory.execute(command, this._visualSchema);
    this._syncVisualSchemaToState();
    this._clearSaveStatus();

    // Auto-select newly added fields
    if (command.type === 'addField') {
      const newFieldId = this._findNewFieldId(prevSchema.fields, this._visualSchema.fields);
      if (newFieldId) this._selectedFieldId = newFieldId;
    }

    // Clear selection if deleted field was selected
    if (command.type === 'deleteField' && this._selectedFieldId === command.fieldId) {
      this._selectedFieldId =
        this._visualSchema.fields.length > 0 ? this._visualSchema.fields[0].id : null;
    }

    // Re-validate examples against the updated schema
    this._validateAllExamples();
  }

  private _selectField(fieldId: string): void {
    this._selectedFieldId = fieldId;
  }

  /** Find a field ID that exists in newFields but not in oldFields (top-level or nested). */
  private _findNewFieldId(
    oldFields: readonly SchemaField[],
    newFields: readonly SchemaField[],
  ): string | null {
    const oldIds = new Set<string>();
    const collectIds = (fields: readonly SchemaField[]) => {
      for (const f of fields) {
        oldIds.add(f.id);
        if ((f.type === 'object' || f.type === 'array') && f.nestedFields) {
          collectIds(f.nestedFields);
        }
      }
    };
    collectIds(oldFields);

    const findNew = (fields: readonly SchemaField[]): string | null => {
      for (const f of fields) {
        if (!oldIds.has(f.id)) return f.id;
        if ((f.type === 'object' || f.type === 'array') && f.nestedFields) {
          const found = findNew(f.nestedFields);
          if (found) return found;
        }
      }
      return null;
    };
    return findNew(newFields);
  }

  private _undo(): void {
    const prev = this._commandHistory.undo(this._visualSchema);
    if (prev) {
      this._visualSchema = prev;
      this._syncVisualSchemaToState();
      this._clearSaveStatus();
      this._validateAllExamples();
    }
  }

  private _redo(): void {
    const next = this._commandHistory.redo(this._visualSchema);
    if (next) {
      this._visualSchema = next;
      this._syncVisualSchemaToState();
      this._clearSaveStatus();
      this._validateAllExamples();
    }
  }

  /**
   * Sync the current VisualSchema to DataContractState for dirty tracking and persistence.
   * Skipped when in json-only mode (raw schema is managed separately).
   */
  private _syncVisualSchemaToState(): void {
    const state = this.contractState!;
    if (state.schemaEditMode === 'json-only') return;

    if (this._visualSchema.fields.length > 0) {
      state.setDraftSchema(visualSchemaToJsonSchema(this._visualSchema));
    } else {
      state.setDraftSchema(null);
    }
  }

  private _toggleFieldExpand(fieldId: string): void {
    const newSet = new Set(this._expandedFields);
    if (newSet.has(fieldId)) {
      newSet.delete(fieldId);
    } else {
      newSet.add(fieldId);
    }
    this._expandedFields = newSet;
  }

  private async _saveAll(): Promise<void> {
    const state = this.contractState!;
    if (this._saving) return;

    // Check for pending migrations before saving
    if (state.isSchemaDirty) {
      const schemaForMigration =
        state.schemaEditMode === 'json-only'
          ? (state.rawJsonSchema as unknown as JsonSchema | null)
          : state.schema;
      const migrations = detectMigrations(schemaForMigration, state.dataExamples);
      if (!migrations.compatible) {
        this._pendingMigrations = migrations.migrations;
        this._selectedMigrations = new Set(
          migrations.migrations.filter((m) => m.autoMigratable).map((m) => migrationKey(m)),
        );
        this._showMigrationDialog = true;
        return;
      }
    }

    await this._executeSave(false);
  }

  private async _forceSave(): Promise<void> {
    this._closeMigrationDialog();
    await this._executeSave(true);
  }

  private async _executeForceSave(): Promise<void> {
    this._canForceSave = false;
    await this._executeSave(true);
  }

  private async _executeSave(forceUpdate: boolean): Promise<void> {
    const state = this.contractState!;
    this._saving = true;
    this._saveSuccess = false;
    this._saveError = null;
    this._canForceSave = false;

    try {
      // Save schema if dirty
      if (state.isSchemaDirty) {
        const schemaResult = await state.saveSchema(forceUpdate);
        if (!schemaResult.success) {
          this._saveError = schemaResult.error ?? 'Failed to save schema';
          if (schemaResult.warnings) {
            this._schemaWarnings = Object.values(schemaResult.warnings).flat();
            // Offer force save when backend rejects with warnings
            this._canForceSave = true;
          }
          return;
        }
        this._commandHistory.clear();
        this._validateAllExamples();
        if (schemaResult.warnings) {
          this._schemaWarnings = Object.values(schemaResult.warnings).flat();
        } else {
          this._schemaWarnings = [];
        }
      }

      // Save all examples if dirty
      if (state.isExamplesDirty) {
        const examplesResult = await state.saveExamples();
        if (!examplesResult.success) {
          this._saveError = examplesResult.error ?? 'Failed to save examples';
          return;
        }
        // Clear all example undo/redo histories on successful save
        this._exampleHistories.clear();
        this._syncExampleUndoRedoState();
      }

      this._saveSuccess = true;
      this._scheduleSuccessClear(() => {
        this._saveSuccess = false;
      });
    } catch (err) {
      this._saveError = err instanceof Error ? err.message : 'Failed to save';
    } finally {
      this._saving = false;
      this.requestUpdate();
    }
  }

  private _clearSaveStatus(): void {
    this._saveSuccess = false;
    this._saveError = null;
    this._canForceSave = false;
  }

  // ---------------------------------------------------------------------------
  // Migration dialog operations
  // ---------------------------------------------------------------------------

  private _toggleMigration(migration: MigrationSuggestion): void {
    const key = migrationKey(migration);
    const newSet = new Set(this._selectedMigrations);
    if (newSet.has(key)) {
      newSet.delete(key);
    } else {
      newSet.add(key);
    }
    this._selectedMigrations = newSet;
  }

  private _selectAllMigrations(): void {
    this._selectedMigrations = new Set(
      this._pendingMigrations.filter((m) => m.autoMigratable).map((m) => migrationKey(m)),
    );
  }

  private _selectNoneMigrations(): void {
    this._selectedMigrations = new Set();
  }

  private async _applyMigrations(selected: MigrationSuggestion[]): Promise<void> {
    const state = this.contractState!;

    // Group migrations by example
    const byExample = new Map<string, MigrationSuggestion[]>();
    for (const m of selected) {
      const existing = byExample.get(m.exampleId) ?? [];
      existing.push(m);
      byExample.set(m.exampleId, existing);
    }

    // Apply migrations to each example
    for (const [exampleId, migrations] of byExample) {
      const example = state.dataExamples.find((e) => e.id === exampleId);
      if (example) {
        const updatedData = applyAllMigrations(example.data, migrations);
        state.updateDraftExample(exampleId, { data: updatedData });
      }
    }

    this._closeMigrationDialog();

    // Now save schema + examples (migrations have been applied to examples)
    await this._executeSave(false);
  }

  private _closeMigrationDialog(): void {
    this._showMigrationDialog = false;
    this._pendingMigrations = [];
    this._selectedMigrations = new Set();
  }

  // ---------------------------------------------------------------------------
  // Example operations
  // ---------------------------------------------------------------------------

  private _selectExample(id: string): void {
    this._editingExampleId = id;
    this._clearSaveStatus();
    this._clearExampleHistory();
    this._syncExampleUndoRedoState();
  }

  private _addExample(): void {
    const state = this.contractState!;
    const newExample: DataExample = {
      id: nanoid(),
      name: `Example ${state.dataExamples.length + 1}`,
      data: {},
    };
    state.addDraftExample(newExample);
    this._editingExampleId = newExample.id;
    this._clearSaveStatus();
    this._clearExampleHistory();
    this._validateAllExamples();
    this._syncExampleUndoRedoState();
  }

  private async _deleteExample(id: string): Promise<void> {
    const state = this.contractState!;

    const result = await state.deleteSingleExample(id);
    if (result.success) {
      // Clean up history for deleted example
      this._exampleHistories.delete(id);

      if (this._editingExampleId === id) {
        const remaining = state.dataExamples;
        this._editingExampleId = remaining.length > 0 ? remaining[0].id : null;
        this._clearExampleHistory();
      }

      this._validateAllExamples();
    }
    this._clearSaveStatus();
    this._syncExampleUndoRedoState();
  }

  private _updateExampleName(id: string, name: string): void {
    const state = this.contractState!;
    state.updateDraftExample(id, { name });
    this._clearSaveStatus();
  }

  private _updateExampleData(id: string, path: string, value: JsonValue): void {
    const state = this.contractState!;
    const example = state.dataExamples.find((e) => e.id === id);
    if (!example) return;

    // Push current data to undo history before mutation
    this._getExampleHistory(id).push(example.data);

    const updatedData = setNestedValue(example.data, path, value);
    state.updateDraftExample(id, { data: updatedData });
    this._clearSaveStatus();
    this._validateAllExamples();
    this._syncExampleUndoRedoState();
  }

  private _undoExampleData(): void {
    if (!this._editingExampleId) return;
    const state = this.contractState!;
    const example = state.dataExamples.find((e) => e.id === this._editingExampleId);
    if (!example) return;

    const history = this._getExampleHistory(this._editingExampleId);
    const prev = history.undo(example.data);
    if (prev) {
      state.updateDraftExample(this._editingExampleId, { data: prev });
      this._validateAllExamples();
      this._syncExampleUndoRedoState();
    }
  }

  private _redoExampleData(): void {
    if (!this._editingExampleId) return;
    const state = this.contractState!;
    const example = state.dataExamples.find((e) => e.id === this._editingExampleId);
    if (!example) return;

    const history = this._getExampleHistory(this._editingExampleId);
    const next = history.redo(example.data);
    if (next) {
      state.updateDraftExample(this._editingExampleId, { data: next });
      this._validateAllExamples();
      this._syncExampleUndoRedoState();
    }
  }

  // ---------------------------------------------------------------------------
  // Example undo/redo helpers
  // ---------------------------------------------------------------------------

  private _getExampleHistory(exampleId: string): SnapshotHistory<JsonObject> {
    let history = this._exampleHistories.get(exampleId);
    if (!history) {
      history = new SnapshotHistory<JsonObject>();
      this._exampleHistories.set(exampleId, history);
    }
    return history;
  }

  private _clearExampleHistory(): void {
    if (this._editingExampleId) {
      this._getExampleHistory(this._editingExampleId).clear();
    }
  }

  private _syncExampleUndoRedoState(): void {
    if (this._editingExampleId) {
      const history = this._getExampleHistory(this._editingExampleId);
      this._exampleCanUndo = history.canUndo;
      this._exampleCanRedo = history.canRedo;
    } else {
      this._exampleCanUndo = false;
      this._exampleCanRedo = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Validation (all examples)
  // ---------------------------------------------------------------------------

  private _validateAllExamples(): void {
    const state = this.contractState!;
    const newErrors = new Map<string, SchemaValidationError[]>();

    if (state.schema) {
      for (const example of state.dataExamples) {
        const result = validateDataAgainstSchema(example.data, state.schema);
        newErrors.set(example.id, result.errors);
      }
    }

    this._exampleValidationErrors = newErrors;
  }

  // ---------------------------------------------------------------------------
  // Event handlers
  // ---------------------------------------------------------------------------

  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this.contractState?.isDirty) {
      e.preventDefault();
    }
  }

  private _handleKeyDown(e: KeyboardEvent): void {
    const isMod = e.metaKey || e.ctrlKey;
    if (!isMod) return;

    if (this._activeTab === 'schema' && this.contractState?.schemaEditMode !== 'json-only') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this._undo();
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this._redo();
      }
    } else if (this._activeTab === 'examples') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this._undoExampleData();
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this._redoExampleData();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Import schema operations
  // ---------------------------------------------------------------------------

  private _openImportDialog(): void {
    this._showImportDialog = true;
    this._importParseError = null;
  }

  private _closeImportDialog(): void {
    this._showImportDialog = false;
    this._importParseError = null;
  }

  private _handleImportFromText(jsonText: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(jsonText);
    } catch {
      this._importParseError = 'Invalid JSON syntax';
      return;
    }

    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      this._importParseError = 'JSON Schema must be a JSON object';
      return;
    }

    this._importSchema(parsed as Record<string, unknown>);
  }

  private async _handleImportFromFile(file: File): Promise<void> {
    try {
      const text = await file.text();
      this._handleImportFromText(text);
    } catch {
      this._importParseError = 'Failed to read file';
    }
  }

  private _importSchema(schema: Record<string, unknown>): void {
    const result = checkSchemaCompatibility(schema);
    this._compatibilityIssues = result.issues;

    const state = this.contractState!;

    // Snapshot current state for undo before applying import
    this._commandHistory.snapshotForImport(this._visualSchema);

    if (result.compatible) {
      // Convert to VisualSchema and load into visual editor
      const visualSchema = jsonSchemaToVisualSchema(schema as unknown as JsonSchema);
      this._visualSchema = visualSchema;
      state.setRawJsonSchema(null, 'visual');
      this._syncVisualSchemaToState();
      this._schemaViewMode = 'visual';
      this._selectedFieldId = visualSchema.fields.length > 0 ? visualSchema.fields[0].id : null;
    } else {
      // Store raw schema, disable visual editor
      state.setRawJsonSchema(schema, 'json-only');
      this._schemaViewMode = 'json';
    }

    this._closeImportDialog();
    this._clearSaveStatus();
    this._validateAllExamples();
  }

  // ---------------------------------------------------------------------------
  // Copy to clipboard
  // ---------------------------------------------------------------------------

  private async _copyJsonSchemaToClipboard(): Promise<void> {
    const state = this.contractState!;
    const schema =
      state.schemaEditMode === 'json-only'
        ? state.rawJsonSchema
        : this._visualSchema.fields.length > 0
          ? visualSchemaToJsonSchema(this._visualSchema)
          : null;

    if (schema) {
      await navigator.clipboard.writeText(JSON.stringify(schema, null, 2));
      this._copySuccess = true;
      this._scheduleSuccessClear(() => {
        this._copySuccess = false;
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private _scheduleSuccessClear(fn: () => void): void {
    if (this._successTimer) clearTimeout(this._successTimer);
    this._successTimer = setTimeout(() => {
      fn();
      this.requestUpdate();
    }, 3000);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-data-contract-editor': EpistolaDataContractEditor;
  }
}
