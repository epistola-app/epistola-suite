/**
 * EpistolaDataContractEditor — Root Lit element for the data contract editor.
 *
 * Two tabs: "Schema" and "Test Data".
 * Owns a DataContractState instance, manages all UI state, delegates
 * rendering to section render functions.
 * Light DOM (no Shadow DOM) for design system CSS integration.
 */

import { LitElement, html, nothing } from 'lit'
import { customElement, state } from 'lit/decorators.js'
import { nanoid } from 'nanoid'
import { DataContractState } from './DataContractState.js'
import type {
  DataExample,
  JsonSchema,
  JsonValue,
  SaveCallbacks,
  SchemaFieldUpdate,
} from './types.js'
import {
  applyFieldUpdate,
  createEmptyField,
  generateSchemaFromData,
  jsonSchemaToVisualSchema,
  visualSchemaToJsonSchema,
} from './utils/schemaUtils.js'
import {
  detectMigrations,
  applyAllMigrations,
  type MigrationSuggestion,
} from './utils/schemaMigration.js'
import { validateDataAgainstSchema } from './utils/schemaValidation.js'
import { renderSchemaSection, type SchemaUiState, type SchemaSectionCallbacks } from './sections/SchemaSection.js'
import { renderExamplesSection, type ExamplesUiState, type ExamplesSectionCallbacks } from './sections/ExamplesSection.js'
import { renderMigrationDialog, migrationKey } from './sections/MigrationAssistant.js'
import { setNestedValue } from './sections/ExampleForm.js'

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
  @state() private _exampleValidationWarnings: Array<{ path: string; message: string }> = []

  // Migration dialog state
  @state() private _showMigrationDialog = false
  @state() private _pendingMigrations: MigrationSuggestion[] = []
  @state() private _selectedMigrations = new Set<string>()

  // Timers
  private _successTimer?: ReturnType<typeof setTimeout>

  // Event listener refs
  private _boundBeforeUnload = this._handleBeforeUnload.bind(this)

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

    // Pre-select first example if available
    if (initialExamples.length > 0) {
      this._editingExampleId = initialExamples[0].id
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override connectedCallback() {
    super.connectedCallback()
    window.addEventListener('beforeunload', this._boundBeforeUnload)
  }

  override disconnectedCallback() {
    super.disconnectedCallback()
    window.removeEventListener('beforeunload', this._boundBeforeUnload)
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
    }

    const callbacks: SchemaSectionCallbacks = {
      onFieldUpdate: (fieldId, updates) => this._updateField(fieldId, updates),
      onFieldDelete: (fieldId) => this._deleteField(fieldId),
      onAddField: () => this._addField(),
      onSave: () => this._saveSchema(),
      onGenerateFromExample: () => this._handleGenerateFromExample(),
      onConfirmGenerate: () => this._confirmGenerate(),
      onCancelGenerate: () => { this._showConfirmGenerate = false },
      onToggleFieldExpand: (fieldId) => this._toggleFieldExpand(fieldId),
    }

    return renderSchemaSection(state, uiState, callbacks, this._expandedFields)
  }

  // ---------------------------------------------------------------------------
  // Examples tab
  // ---------------------------------------------------------------------------

  private _renderExamplesTab(): unknown {
    const state = this.contractState!

    const uiState: ExamplesUiState = {
      editingId: this._editingExampleId,
      isSaving: this._exampleSaving,
      saveSuccess: this._exampleSaveSuccess,
      saveError: this._exampleSaveError,
      validationWarnings: this._exampleValidationWarnings,
    }

    const callbacks: ExamplesSectionCallbacks = {
      onSelectExample: (id) => this._selectExample(id),
      onAddExample: () => this._addExample(),
      onDeleteExample: (id) => this._deleteExample(id),
      onUpdateExampleName: (id, name) => this._updateExampleName(id, name),
      onUpdateExampleData: (id, path, value) => this._updateExampleData(id, path, value),
      onSaveExample: (id) => this._saveExample(id),
    }

    return renderExamplesSection(state, uiState, callbacks)
  }

  // ---------------------------------------------------------------------------
  // Schema operations
  // ---------------------------------------------------------------------------

  private _updateField(fieldId: string, updates: SchemaFieldUpdate): void {
    const state = this.contractState!
    const visual = jsonSchemaToVisualSchema(state.schema)
    const updatedFields = visual.fields.map((f) =>
      f.id === fieldId ? applyFieldUpdate(f, updates) : f,
    )
    state.setDraftSchema(visualSchemaToJsonSchema({ fields: updatedFields }))
    this._clearSchemaSaveStatus()
  }

  private _deleteField(fieldId: string): void {
    const state = this.contractState!
    const visual = jsonSchemaToVisualSchema(state.schema)
    const updatedFields = visual.fields.filter((f) => f.id !== fieldId)
    if (updatedFields.length === 0) {
      state.setDraftSchema(null)
    } else {
      state.setDraftSchema(visualSchemaToJsonSchema({ fields: updatedFields }))
    }
    this._expandedFields.delete(fieldId)
    this._clearSchemaSaveStatus()
  }

  private _addField(): void {
    const state = this.contractState!
    const visual = jsonSchemaToVisualSchema(state.schema)
    const newField = createEmptyField(`field${visual.fields.length + 1}`)
    const updatedFields = [...visual.fields, newField]
    state.setDraftSchema(visualSchemaToJsonSchema({ fields: updatedFields }))
    this._clearSchemaSaveStatus()
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
    const state = this.contractState!
    const hasExistingSchema = state.schema !== null && state.schema.properties &&
      Object.keys(state.schema.properties).length > 0

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
    const visual = generateSchemaFromData(firstExample.data)
    state.setDraftSchema(visualSchemaToJsonSchema(visual))
    this._clearSchemaSaveStatus()
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

        // Re-validate examples after schema save
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
    this._validateCurrentExample()
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
  }

  private async _deleteExample(id: string): Promise<void> {
    const state = this.contractState!

    const result = await state.deleteSingleExample(id)
    if (result.success) {
      if (this._editingExampleId === id) {
        const remaining = state.dataExamples
        this._editingExampleId = remaining.length > 0 ? remaining[0].id : null
      }
    }
    this._clearExampleSaveStatus()
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

    const updatedData = setNestedValue(example.data, path, value)
    state.updateDraftExample(id, { data: updatedData })
    this._clearExampleSaveStatus()
    this._validateCurrentExample()
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

        if (result.warnings) {
          this._exampleValidationWarnings = Object.values(result.warnings).flat()
        } else {
          this._exampleValidationWarnings = []
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

  private _validateCurrentExample(): void {
    const state = this.contractState!
    if (!this._editingExampleId || !state.schema) {
      this._exampleValidationWarnings = []
      return
    }

    const example = state.dataExamples.find((e) => e.id === this._editingExampleId)
    if (!example) {
      this._exampleValidationWarnings = []
      return
    }

    const result = validateDataAgainstSchema(example.data, state.schema)
    this._exampleValidationWarnings = result.errors
  }

  // ---------------------------------------------------------------------------
  // Event handlers
  // ---------------------------------------------------------------------------

  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this.contractState?.isDirty) {
      e.preventDefault()
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
