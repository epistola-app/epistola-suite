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

import { LitElement, html, nothing } from 'lit'
import { customElement, state } from 'lit/decorators.js'
import { nanoid } from 'nanoid'
import { DataContractState } from './DataContractState.js'
import type {
  DataExample,
  JsonObject,
  JsonSchema,
  JsonValue,
  SaveCallbacks,
  VisualSchema,
} from './types.js'
import {
  jsonSchemaToVisualSchema,
  visualSchemaToJsonSchema,
} from './utils/schemaUtils.js'
import type { SchemaCommand } from './utils/schemaCommands.js'
import { SchemaCommandHistory } from './utils/schemaCommandHistory.js'
import { SnapshotHistory } from './utils/snapshotHistory.js'
import {
  detectMigrations,
  applyAllMigrations,
  type MigrationSuggestion,
} from './utils/schemaMigration.js'
import { validateDataAgainstSchema, type SchemaValidationError } from './utils/schemaValidation.js'
import { renderSchemaSection, type SchemaUiState, type SchemaSectionCallbacks } from './sections/SchemaSection.js'
import { renderExamplesSection, type ExamplesUiState, type ExamplesSectionCallbacks } from './sections/ExamplesSection.js'
import { renderMigrationDialog, migrationKey } from './sections/MigrationAssistant.js'
import { setNestedValue, buildFieldErrorMap } from './sections/ExampleForm.js'

type TabId = 'schema' | 'examples'

@customElement('epistola-data-contract-editor')
export class EpistolaDataContractEditor extends LitElement {
  override createRenderRoot() {
    return this
  }

  // ---------------------------------------------------------------------------
  // External state (injected via init())
  // ---------------------------------------------------------------------------

  contractState?: DataContractState

  // ---------------------------------------------------------------------------
  // Schema editing state (VisualSchema is the primary editing state)
  // ---------------------------------------------------------------------------

  @state() private _visualSchema: VisualSchema = { fields: [] }
  private _commandHistory = new SchemaCommandHistory()

  // ---------------------------------------------------------------------------
  // UI state (reactive via @state())
  // ---------------------------------------------------------------------------

  @state() private _activeTab: TabId = 'schema'

  // Schema tab UI state
  @state() private _schemaSaving = false
  @state() private _schemaSaveSuccess = false
  @state() private _schemaSaveError: string | null = null
  @state() private _schemaWarnings: Array<{ path: string; message: string }> = []
  @state() private _showConfirmGenerate = false
  @state() private _expandedFields = new Set<string>()

  // Examples tab UI state
  @state() private _editingExampleId: string | null = null
  @state() private _exampleSaving = false
  @state() private _exampleSaveSuccess = false
  @state() private _exampleSaveError: string | null = null

  // Per-example undo/redo stacks
  private _exampleHistories = new Map<string, SnapshotHistory<JsonObject>>()
  @state() private _exampleCanUndo = false
  @state() private _exampleCanRedo = false

  // Validation errors for all examples (keyed by example ID)
  @state() private _exampleValidationErrors = new Map<string, SchemaValidationError[]>()

  // Migration dialog state
  @state() private _showMigrationDialog = false
  @state() private _pendingMigrations: MigrationSuggestion[] = []
  @state() private _selectedMigrations = new Set<string>()

  // Timers
  private _successTimer?: ReturnType<typeof setTimeout>

  // Event listener refs
  private _boundBeforeUnload = this._handleBeforeUnload.bind(this)
  private _boundKeyDown = this._handleKeyDown.bind(this)

  // ---------------------------------------------------------------------------
  // Initialization
  // ---------------------------------------------------------------------------

  init(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ): void {
    this.contractState = new DataContractState(initialSchema, initialExamples, callbacks)

    this.contractState.addEventListener('change', () => {
      this.requestUpdate()
    })

    // Convert initial JSON Schema to VisualSchema once — this is now the primary editing state
    this._visualSchema = jsonSchemaToVisualSchema(initialSchema)
    this._commandHistory.clear()

    // Pre-select first example if available
    if (initialExamples.length > 0) {
      this._editingExampleId = initialExamples[0].id
    }

    // Validate all examples on init
    this._validateAllExamples()
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override connectedCallback() {
    super.connectedCallback()
    window.addEventListener('beforeunload', this._boundBeforeUnload)
    window.addEventListener('keydown', this._boundKeyDown)
  }

  override disconnectedCallback() {
    super.disconnectedCallback()
    window.removeEventListener('beforeunload', this._boundBeforeUnload)
    window.removeEventListener('keydown', this._boundKeyDown)
    if (this._successTimer) clearTimeout(this._successTimer)
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    if (!this.contractState) {
      return html`<div class="dc-empty-state">No data contract loaded.</div>`
    }

    return html`
      <div class="dc-editor-layout">
        <!-- Tab bar -->
        <div class="dc-tabs" role="tablist">
          ${this._renderTab('schema', 'Schema')}
          ${this._renderTab('examples', 'Test Data')}
        </div>

        <!-- Tab content -->
        <div class="dc-tab-content">
          ${this._activeTab === 'schema'
            ? this._renderSchemaTab()
            : this._renderExamplesTab()
          }
        </div>
      </div>

      <!-- Migration dialog -->
      ${this._showMigrationDialog
        ? html`
            <dialog class="dc-dialog" open @close=${() => this._closeMigrationDialog()}>
              ${renderMigrationDialog(
                this._pendingMigrations,
                this._selectedMigrations,
                {
                  onApply: (selected) => this._applyMigrations(selected),
                  onForceSave: () => this._forceSaveSchema(),
                  onCancel: () => this._closeMigrationDialog(),
                  onToggleMigration: (m) => this._toggleMigration(m),
                  onSelectAll: () => this._selectAllMigrations(),
                  onSelectNone: () => this._selectNoneMigrations(),
                },
              )}
            </dialog>
          `
        : nothing
      }
    `
  }

  private _renderTab(tabId: TabId, label: string): unknown {
    const isActive = this._activeTab === tabId
    return html`
      <button
        class="dc-tab ${isActive ? 'dc-tab-active' : ''}"
        role="tab"
        aria-selected="${isActive}"
        @click=${() => { this._activeTab = tabId }}
      >${label}</button>
    `
  }

  // ---------------------------------------------------------------------------
  // Schema tab
  // ---------------------------------------------------------------------------

  private _renderSchemaTab(): unknown {
    const state = this.contractState!

    const uiState: SchemaUiState = {
      isSaving: this._schemaSaving,
      saveSuccess: this._schemaSaveSuccess,
      saveError: this._schemaSaveError,
      warnings: this._schemaWarnings,
      showConfirmGenerate: this._showConfirmGenerate,
      showMigrationAssistant: this._showMigrationDialog,
      pendingMigrations: this._pendingMigrations,
      canUndo: this._commandHistory.canUndo,
      canRedo: this._commandHistory.canRedo,
    }

    const callbacks: SchemaSectionCallbacks = {
      onCommand: (command) => this._executeCommand(command),
      onSave: () => this._saveSchema(),
      onGenerateFromExample: () => this._handleGenerateFromExample(),
      onConfirmGenerate: () => this._confirmGenerate(),
      onCancelGenerate: () => { this._showConfirmGenerate = false },
      onToggleFieldExpand: (fieldId) => this._toggleFieldExpand(fieldId),
      onUndo: () => this._undo(),
      onRedo: () => this._redo(),
    }

    return renderSchemaSection(this._visualSchema, state, uiState, callbacks, this._expandedFields)
  }

  // ---------------------------------------------------------------------------
  // Examples tab
  // ---------------------------------------------------------------------------

  private _renderExamplesTab(): unknown {
    const state = this.contractState!

    // Derive errors for selected example
    const errorsForSelected = this._editingExampleId
      ? (this._exampleValidationErrors.get(this._editingExampleId) ?? [])
      : []
    const fieldErrorMap = buildFieldErrorMap(errorsForSelected)

    // Derive error counts per example for chip badges
    const exampleErrorCounts: Record<string, number> = {}
    for (const ex of state.dataExamples) {
      exampleErrorCounts[ex.id] = (this._exampleValidationErrors.get(ex.id) ?? []).length
    }

    const uiState: ExamplesUiState = {
      editingId: this._editingExampleId,
      isSaving: this._exampleSaving,
      saveSuccess: this._exampleSaveSuccess,
      saveError: this._exampleSaveError,
      fieldErrorMap,
      validationErrorCount: errorsForSelected.length,
      exampleErrorCounts,
      canUndo: this._exampleCanUndo,
      canRedo: this._exampleCanRedo,
    }

    const callbacks: ExamplesSectionCallbacks = {
      onSelectExample: (id) => this._selectExample(id),
      onAddExample: () => this._addExample(),
      onDeleteExample: (id) => this._deleteExample(id),
      onUpdateExampleName: (id, name) => this._updateExampleName(id, name),
      onUpdateExampleData: (id, path, value) => this._updateExampleData(id, path, value),
      onSaveExample: (id) => this._saveExample(id),
      onUndo: () => this._undoExampleData(),
      onRedo: () => this._redoExampleData(),
    }

    return renderExamplesSection(state, uiState, callbacks)
  }

  // ---------------------------------------------------------------------------
  // Schema operations (command-based)
  // ---------------------------------------------------------------------------

  private _executeCommand(command: SchemaCommand): void {
    this._visualSchema = this._commandHistory.execute(command, this._visualSchema)
    this._syncVisualSchemaToState()
    this._clearSchemaSaveStatus()
  }

  private _undo(): void {
    const prev = this._commandHistory.undo(this._visualSchema)
    if (prev) {
      this._visualSchema = prev
      this._syncVisualSchemaToState()
      this._clearSchemaSaveStatus()
    }
  }

  private _redo(): void {
    const next = this._commandHistory.redo(this._visualSchema)
    if (next) {
      this._visualSchema = next
      this._syncVisualSchemaToState()
      this._clearSchemaSaveStatus()
    }
  }

  /**
   * Sync the current VisualSchema to DataContractState for dirty tracking and persistence.
   */
  private _syncVisualSchemaToState(): void {
    const state = this.contractState!
    if (this._visualSchema.fields.length > 0) {
      state.setDraftSchema(visualSchemaToJsonSchema(this._visualSchema))
    } else {
      state.setDraftSchema(null)
    }
  }

  private _toggleFieldExpand(fieldId: string): void {
    const newSet = new Set(this._expandedFields)
    if (newSet.has(fieldId)) {
      newSet.delete(fieldId)
    } else {
      newSet.add(fieldId)
    }
    this._expandedFields = newSet
  }

  private _handleGenerateFromExample(): void {
    const hasExistingSchema = this._visualSchema.fields.length > 0

    if (hasExistingSchema) {
      this._showConfirmGenerate = true
    } else {
      this._confirmGenerate()
    }
  }

  private _confirmGenerate(): void {
    const state = this.contractState!
    this._showConfirmGenerate = false

    if (state.dataExamples.length === 0) return

    const firstExample = state.dataExamples[0]
    this._executeCommand({ type: 'generateFromExample', data: firstExample.data })
  }

  private async _saveSchema(): Promise<void> {
    const state = this.contractState!
    if (this._schemaSaving) return

    // Check for pending migrations before saving
    const migrations = detectMigrations(state.schema, state.dataExamples)
    if (!migrations.compatible) {
      this._pendingMigrations = migrations.migrations
      this._selectedMigrations = new Set(
        migrations.migrations
          .filter((m) => m.autoMigratable)
          .map((m) => migrationKey(m)),
      )
      this._showMigrationDialog = true
      return
    }

    await this._executeSaveSchema(false)
  }

  private async _forceSaveSchema(): Promise<void> {
    this._closeMigrationDialog()
    await this._executeSaveSchema(true)
  }

  private async _executeSaveSchema(forceUpdate: boolean): Promise<void> {
    const state = this.contractState!
    this._schemaSaving = true
    this._schemaSaveSuccess = false
    this._schemaSaveError = null

    try {
      const result = await state.saveSchema(forceUpdate)
      if (result.success) {
        this._schemaSaveSuccess = true
        this._scheduleSuccessClear(() => { this._schemaSaveSuccess = false })
        this._commandHistory.clear()

        // Re-validate all examples after schema save
        this._validateAllExamples()

        if (result.warnings) {
          this._schemaWarnings = Object.values(result.warnings).flat()
        } else {
          this._schemaWarnings = []
        }
      } else {
        this._schemaSaveError = result.error ?? 'Failed to save schema'
        if (result.warnings) {
          this._schemaWarnings = Object.values(result.warnings).flat()
        }
      }
    } catch (err) {
      this._schemaSaveError = err instanceof Error ? err.message : 'Failed to save schema'
    } finally {
      this._schemaSaving = false
      this.requestUpdate()
    }
  }

  private _clearSchemaSaveStatus(): void {
    this._schemaSaveSuccess = false
    this._schemaSaveError = null
  }

  // ---------------------------------------------------------------------------
  // Migration dialog operations
  // ---------------------------------------------------------------------------

  private _toggleMigration(migration: MigrationSuggestion): void {
    const key = migrationKey(migration)
    const newSet = new Set(this._selectedMigrations)
    if (newSet.has(key)) {
      newSet.delete(key)
    } else {
      newSet.add(key)
    }
    this._selectedMigrations = newSet
  }

  private _selectAllMigrations(): void {
    this._selectedMigrations = new Set(
      this._pendingMigrations
        .filter((m) => m.autoMigratable)
        .map((m) => migrationKey(m)),
    )
  }

  private _selectNoneMigrations(): void {
    this._selectedMigrations = new Set()
  }

  private async _applyMigrations(selected: MigrationSuggestion[]): Promise<void> {
    const state = this.contractState!

    // Group migrations by example
    const byExample = new Map<string, MigrationSuggestion[]>()
    for (const m of selected) {
      const existing = byExample.get(m.exampleId) ?? []
      existing.push(m)
      byExample.set(m.exampleId, existing)
    }

    // Apply migrations to each example
    for (const [exampleId, migrations] of byExample) {
      const example = state.dataExamples.find((e) => e.id === exampleId)
      if (example) {
        const updatedData = applyAllMigrations(example.data, migrations)
        state.updateDraftExample(exampleId, { data: updatedData })
      }
    }

    this._closeMigrationDialog()

    // Now save the schema (migrations have been applied to examples)
    await this._executeSaveSchema(false)
  }

  private _closeMigrationDialog(): void {
    this._showMigrationDialog = false
    this._pendingMigrations = []
    this._selectedMigrations = new Set()
  }

  // ---------------------------------------------------------------------------
  // Example operations
  // ---------------------------------------------------------------------------

  private _selectExample(id: string): void {
    this._editingExampleId = id
    this._clearExampleSaveStatus()
    this._clearExampleHistory()
    this._syncExampleUndoRedoState()
  }

  private _addExample(): void {
    const state = this.contractState!
    const newExample: DataExample = {
      id: nanoid(),
      name: `Example ${state.dataExamples.length + 1}`,
      data: {},
    }
    state.addDraftExample(newExample)
    this._editingExampleId = newExample.id
    this._clearExampleSaveStatus()
    this._clearExampleHistory()
    this._validateAllExamples()
    this._syncExampleUndoRedoState()
  }

  private async _deleteExample(id: string): Promise<void> {
    const state = this.contractState!

    const result = await state.deleteSingleExample(id)
    if (result.success) {
      // Clean up history for deleted example
      this._exampleHistories.delete(id)

      if (this._editingExampleId === id) {
        const remaining = state.dataExamples
        this._editingExampleId = remaining.length > 0 ? remaining[0].id : null
        this._clearExampleHistory()
      }

      this._validateAllExamples()
    }
    this._clearExampleSaveStatus()
    this._syncExampleUndoRedoState()
  }

  private _updateExampleName(id: string, name: string): void {
    const state = this.contractState!
    state.updateDraftExample(id, { name })
    this._clearExampleSaveStatus()
  }

  private _updateExampleData(id: string, path: string, value: JsonValue): void {
    const state = this.contractState!
    const example = state.dataExamples.find((e) => e.id === id)
    if (!example) return

    // Push current data to undo history before mutation
    this._getExampleHistory(id).push(example.data)

    const updatedData = setNestedValue(example.data, path, value)
    state.updateDraftExample(id, { data: updatedData })
    this._clearExampleSaveStatus()
    this._validateAllExamples()
    this._syncExampleUndoRedoState()
  }

  private _undoExampleData(): void {
    if (!this._editingExampleId) return
    const state = this.contractState!
    const example = state.dataExamples.find((e) => e.id === this._editingExampleId)
    if (!example) return

    const history = this._getExampleHistory(this._editingExampleId)
    const prev = history.undo(example.data)
    if (prev) {
      state.updateDraftExample(this._editingExampleId, { data: prev })
      this._validateAllExamples()
      this._syncExampleUndoRedoState()
    }
  }

  private _redoExampleData(): void {
    if (!this._editingExampleId) return
    const state = this.contractState!
    const example = state.dataExamples.find((e) => e.id === this._editingExampleId)
    if (!example) return

    const history = this._getExampleHistory(this._editingExampleId)
    const next = history.redo(example.data)
    if (next) {
      state.updateDraftExample(this._editingExampleId, { data: next })
      this._validateAllExamples()
      this._syncExampleUndoRedoState()
    }
  }

  private async _saveExample(id: string): Promise<void> {
    const state = this.contractState!
    if (this._exampleSaving) return

    const example = state.dataExamples.find((e) => e.id === id)
    if (!example) return

    this._exampleSaving = true
    this._exampleSaveSuccess = false
    this._exampleSaveError = null

    try {
      const result = await state.saveSingleExample(id, {
        name: example.name,
        data: example.data,
      })

      if (result.success) {
        this._exampleSaveSuccess = true
        this._scheduleSuccessClear(() => { this._exampleSaveSuccess = false })

        // Clear undo/redo history on successful save
        this._getExampleHistory(id).clear()
        this._syncExampleUndoRedoState()

        if (result.warnings) {
          // Re-validate to update inline errors from server-side warnings
          this._validateAllExamples()
        }
      } else {
        const errors = result.errors ? Object.values(result.errors).flat() : []
        this._exampleSaveError = errors.length > 0
          ? errors.map((e) => `${e.path}: ${e.message}`).join('; ')
          : 'Failed to save example'
      }
    } catch (err) {
      this._exampleSaveError = err instanceof Error ? err.message : 'Failed to save example'
    } finally {
      this._exampleSaving = false
      this.requestUpdate()
    }
  }

  private _clearExampleSaveStatus(): void {
    this._exampleSaveSuccess = false
    this._exampleSaveError = null
  }

  // ---------------------------------------------------------------------------
  // Example undo/redo helpers
  // ---------------------------------------------------------------------------

  private _getExampleHistory(exampleId: string): SnapshotHistory<JsonObject> {
    let history = this._exampleHistories.get(exampleId)
    if (!history) {
      history = new SnapshotHistory<JsonObject>()
      this._exampleHistories.set(exampleId, history)
    }
    return history
  }

  private _clearExampleHistory(): void {
    if (this._editingExampleId) {
      this._getExampleHistory(this._editingExampleId).clear()
    }
  }

  private _syncExampleUndoRedoState(): void {
    if (this._editingExampleId) {
      const history = this._getExampleHistory(this._editingExampleId)
      this._exampleCanUndo = history.canUndo
      this._exampleCanRedo = history.canRedo
    } else {
      this._exampleCanUndo = false
      this._exampleCanRedo = false
    }
  }

  // ---------------------------------------------------------------------------
  // Validation (all examples)
  // ---------------------------------------------------------------------------

  private _validateAllExamples(): void {
    const state = this.contractState!
    const newErrors = new Map<string, SchemaValidationError[]>()

    if (state.schema) {
      for (const example of state.dataExamples) {
        const result = validateDataAgainstSchema(example.data, state.schema)
        newErrors.set(example.id, result.errors)
      }
    }

    this._exampleValidationErrors = newErrors
  }

  // ---------------------------------------------------------------------------
  // Event handlers
  // ---------------------------------------------------------------------------

  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this.contractState?.isDirty) {
      e.preventDefault()
    }
  }

  private _handleKeyDown(e: KeyboardEvent): void {
    const isMod = e.metaKey || e.ctrlKey
    if (!isMod) return

    if (this._activeTab === 'schema') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault()
        this._undo()
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault()
        this._redo()
      }
    } else if (this._activeTab === 'examples') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault()
        this._undoExampleData()
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault()
        this._redoExampleData()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private _scheduleSuccessClear(fn: () => void): void {
    if (this._successTimer) clearTimeout(this._successTimer)
    this._successTimer = setTimeout(() => {
      fn()
      this.requestUpdate()
    }, 3000)
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-data-contract-editor': EpistolaDataContractEditor
  }
}
