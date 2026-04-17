/**
 * EpistolaDataContractEditor — Root Lit element for the data contract editor.
 *
 * Thin shell: delegates to DataContractStore for state, SaveOrchestrator for
 * save logic, and section render functions for UI.
 * Light DOM (no Shadow DOM) for design system CSS integration.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { nanoid } from 'nanoid';
import { icon } from '../ui/icons.js';
import { DataContractStore } from './DataContractStore.js';
import { orchestrateSave, executeSave, flattenCompatibilityWarnings } from './SaveOrchestrator.js';
import type { DataExample, JsonSchema, SaveCallbacks } from './types.js';
import { checkSchemaCompatibility, type CompatibilityIssue } from './utils/schemaCompatibility.js';
import { jsonSchemaToVisualSchema, visualSchemaToJsonSchema } from './utils/schemaUtils.js';
import { buildFieldErrorMap } from './sections/ExampleForm.js';
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
import { renderSchemaFixScreen } from './sections/SchemaFixScreen.js';
import { renderJsonSchemaView } from './sections/JsonSchemaView.js';
import { renderImportSchemaDialog } from './sections/ImportSchemaDialog.js';
import { getActiveSchema, isRootJsonSchema } from './utils/activeSchema.js';

type TabId = 'schema' | 'examples';

@customElement('epistola-data-contract-editor')
export class EpistolaDataContractEditor extends LitElement {
  override createRenderRoot() {
    return this;
  }

  store = new DataContractStore();

  // Local UI state (not worth moving to store)
  @state() private _showImportDialog = false;
  @state() private _importParseError: string | null = null;
  @state() private _copySuccess = false;
  @state() private _compatibilityIssues: CompatibilityIssue[] = [];
  private _successTimer?: ReturnType<typeof setTimeout>;
  private _boundBeforeUnload = this._handleBeforeUnload.bind(this);
  private _boundKeyDown = this._handleKeyDown.bind(this);

  // Keep contractState for backward compat (ExamplesSection reads it)
  get contractState(): DataContractStore {
    return this.store;
  }

  init(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ): void {
    this.store.setHost(this);
    this.store.init(initialSchema, initialExamples, callbacks);

    if (initialSchema) {
      const compat = checkSchemaCompatibility(initialSchema);
      this._compatibilityIssues = compat.issues;
    }
  }

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
    const s = this.store.state;
    const saveStatus = s.saveStatus;

    return html`
      <div class="dc-editor-layout">
        <!-- Tab bar -->
        <div class="dc-tabs" role="tablist">
          ${this._renderTab('schema', 'Schema')} ${this._renderTab('examples', 'Test Data')}

          <div class="dc-tabs-spacer"></div>

          <!-- Unified save -->
          ${saveStatus.type === 'success'
            ? html`<span class="dc-status-success">Saved successfully</span>`
            : nothing}
          ${saveStatus.type === 'error'
            ? html`<span class="dc-status-error">${saveStatus.message}</span>`
            : nothing}
          ${saveStatus.type === 'error' && saveStatus.canForceSave
            ? html`<button
                class="ep-btn-outline btn-sm dc-force-save-btn"
                @click=${() => this._saveAll(true)}
              >
                Save Anyway
              </button>`
            : nothing}
          <button
            class="ep-btn-primary btn-sm dc-save-btn"
            ?disabled=${saveStatus.type === 'saving' || !this.store.isDirty}
            @click=${() => this._saveAll(false)}
          >
            ${saveStatus.type === 'saving' ? 'Saving...' : 'Save'}
          </button>
        </div>

        <!-- Tab content -->
        <div class="dc-tab-content">
          ${s.activeTab === 'schema' ? this._renderSchemaTab() : this._renderExamplesTab()}
        </div>
      </div>

      <!-- Schema fix screen overlay -->
      ${s.fixScreen
        ? renderSchemaFixScreen(
            s.fixScreen.migrations,
            s.examples,
            s.fixScreen.newSchema,
            s.fixScreen.fields,
            s.fixScreen.errors,
            s.fixScreen.editedData,
            s.schemaWarnings,
            s.saveStatus.type === 'error' ? s.saveStatus.message : null,
            s.saveStatus.type === 'error' ? s.saveStatus.canForceSave : false,
            {
              onFieldChange: (exampleId, path, value) =>
                this.store.dispatch({ type: 'fix-field-change', exampleId, path, value }),
              onRemoveField: (exampleId, path) =>
                this.store.dispatch({ type: 'fix-remove-field', exampleId, path }),
              onRemoveAllUnknown: () => this.store.dispatch({ type: 'fix-remove-all-unknown' }),
              onRevert: () => this._handleFixRevert(),
              onContinue: () => this._handleFixContinue(),
              onForceSave: () => this._handleFixForceSave(),
              onCancel: () => this.store.dispatch({ type: 'close-fix-screen' }),
            },
          )
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
    const isActive = this.store.state.activeTab === tabId;
    return html`
      <button
        class="dc-tab ${isActive ? 'dc-tab-active' : ''}"
        role="tab"
        aria-selected="${isActive}"
        @click=${() => this.store.dispatch({ type: 'select-tab', tab: tabId })}
      >
        ${label}
      </button>
    `;
  }

  // ---------------------------------------------------------------------------
  // Schema tab
  // ---------------------------------------------------------------------------

  private _renderSchemaTab(): unknown {
    const s = this.store.state;
    const isJsonOnly = s.schemaEditMode === 'json-only';

    const jsonSchemaViewCallbacks = {
      onCopyToClipboard: () => this._copyJsonSchemaToClipboard(),
      onImportSchema: () => this._openImportDialog(),
    };

    if (isJsonOnly) {
      return renderJsonSchemaView(
        s.rawJsonSchema,
        this._compatibilityIssues,
        this._copySuccess,
        jsonSchemaViewCallbacks,
      );
    }

    return html`
      <!-- View toggle -->
      <div class="dc-toolbar">
        <div class="dc-schema-view-toggle">
          <button
            class="dc-schema-view-toggle-btn ${s.schemaViewMode === 'visual'
              ? 'dc-schema-view-toggle-btn-active'
              : ''}"
            @click=${() => this.store.dispatch({ type: 'set-schema-view-mode', mode: 'visual' })}
          >
            Visual
          </button>
          <button
            class="dc-schema-view-toggle-btn ${s.schemaViewMode === 'json'
              ? 'dc-schema-view-toggle-btn-active'
              : ''}"
            @click=${() => this.store.dispatch({ type: 'set-schema-view-mode', mode: 'json' })}
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

      ${s.schemaViewMode === 'json'
        ? renderJsonSchemaView(
            s.visualSchema.fields.length > 0 ? visualSchemaToJsonSchema(s.visualSchema) : null,
            [],
            this._copySuccess,
            jsonSchemaViewCallbacks,
          )
        : this._renderVisualSchemaSection()}
    `;
  }

  private _renderVisualSchemaSection(): unknown {
    const s = this.store.state;

    const uiState: SchemaUiState = {
      warnings: s.schemaWarnings,
      canUndo: s.schemaCommandHistory.canUndo,
      canRedo: s.schemaCommandHistory.canRedo,
      selectedFieldId: s.selectedFieldId,
    };

    const callbacks: SchemaSectionCallbacks = {
      onCommand: (command) => this.store.dispatch({ type: 'execute-schema-command', command }),
      onToggleFieldExpand: (fieldId) =>
        this.store.dispatch({ type: 'toggle-field-expand', fieldId }),
      onSelectField: (fieldId) => this.store.dispatch({ type: 'select-field', fieldId }),
      onUndo: () => this.store.dispatch({ type: 'undo-schema' }),
      onRedo: () => this.store.dispatch({ type: 'redo-schema' }),
      onReviewWarnings: () => this._openWarningsModal(),
    };

    return renderSchemaSection(s.visualSchema, uiState, callbacks, s.expandedFields);
  }

  // ---------------------------------------------------------------------------
  // Examples tab
  // ---------------------------------------------------------------------------

  private _renderExamplesTab(): unknown {
    const s = this.store.state;

    // Derive errors for selected example
    const errorsForSelected = s.selectedExampleId
      ? (s.validationErrors.get(s.selectedExampleId) ?? [])
      : [];
    const fieldErrorMap = buildFieldErrorMap(errorsForSelected);

    // Derive error counts per example for chip badges
    const exampleErrorCounts: Record<string, number> = {};
    for (const ex of s.examples) {
      exampleErrorCounts[ex.id] = (s.validationErrors.get(ex.id) ?? []).length;
    }

    const uiState: ExamplesUiState = {
      editingId: s.selectedExampleId,
      fieldErrorMap,
      validationErrorCount: errorsForSelected.length,
      exampleErrorCounts,
      canUndo: this.store.exampleCanUndo,
      canRedo: this.store.exampleCanRedo,
    };

    const callbacks: ExamplesSectionCallbacks = {
      onSelectExample: (id) => this.store.dispatch({ type: 'select-example', exampleId: id }),
      onAddExample: () => this._addExample(),
      onDeleteExample: (id) => this.store.dispatch({ type: 'delete-example', exampleId: id }),
      onUpdateExampleName: (id, name) =>
        this.store.dispatch({ type: 'update-example-name', exampleId: id, name }),
      onUpdateExampleData: (id, path, value) =>
        this.store.dispatch({ type: 'update-example-data', exampleId: id, path, value }),
      onClearExampleData: (id, path) =>
        this.store.dispatch({ type: 'clear-example-field', exampleId: id, path }),
      onUndo: () => this.store.dispatch({ type: 'undo-example' }),
      onRedo: () => this.store.dispatch({ type: 'redo-example' }),
    };

    // Create a state-like object for ExamplesSection (it reads .dataExamples and .schema)
    const stateForSection = {
      dataExamples: s.examples,
      schema: s.schema,
    };

    return renderExamplesSection(
      stateForSection as Parameters<typeof renderExamplesSection>[0],
      uiState,
      callbacks,
    );
  }

  // ---------------------------------------------------------------------------
  // Save
  // ---------------------------------------------------------------------------

  private async _saveAll(forceSave: boolean): Promise<void> {
    const intent = forceSave ? { type: 'force-save' as const } : { type: 'save' as const };

    // Compatibility check (only for non-force saves with dirty schema)
    let compatibilityResult;
    if (!forceSave && this.store.isSchemaDirty) {
      compatibilityResult = await this.store.validateSchemaCompatibility();
    }

    const outcome = orchestrateSave(this.store, intent, compatibilityResult);

    if (outcome.action === 'open-fix-screen') {
      // Orchestrator decided to show fix screen. Store will handle state.
      await executeSave(this.store, outcome);
      return;
    }

    if (outcome.action === 'error') {
      this.store.dispatch({
        type: 'set-schema-warnings',
        warnings: compatibilityResult ? flattenCompatibilityWarnings(compatibilityResult) : [],
      });
      await executeSave(this.store, outcome);
      this._openWarningsModal();
      return;
    }

    // Clear warnings on successful compatibility check
    if (compatibilityResult?.compatible) {
      this.store.dispatch({ type: 'set-schema-warnings', warnings: [] });
    }

    await executeSave(this.store, outcome);

    // Auto-clear success after 3 seconds
    if (this.store.state.saveStatus.type === 'success') {
      this._scheduleSuccessClear(() => {
        this.store.dispatch({ type: 'clear-save-status' });
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Fix screen
  // ---------------------------------------------------------------------------

  private async _handleFixContinue(): Promise<void> {
    const valid = this.store.validateFixScreenFields();
    if (!valid) return;

    const outcome = orchestrateSave(this.store, { type: 'fix-and-save' });
    const result = await executeSave(this.store, outcome);

    if (result.success) {
      this.store.dispatch({ type: 'close-fix-screen' });
      this._scheduleSuccessClear(() => {
        this.store.dispatch({ type: 'clear-save-status' });
      });
    }
    // On failure: fix screen stays open, error is shown in the save status
  }

  private async _handleFixForceSave(): Promise<void> {
    const fixedExamples = this.store.buildFixedExamples();
    const outcome =
      fixedExamples && fixedExamples.length > 0
        ? { action: 'save-schema' as const, force: true, examples: fixedExamples }
        : orchestrateSave(this.store, { type: 'force-save' });
    const result = await executeSave(this.store, outcome);

    if (result.success) {
      this.store.dispatch({ type: 'close-fix-screen' });
      this._scheduleSuccessClear(() => {
        this.store.dispatch({ type: 'clear-save-status' });
      });
    }
  }

  private _handleFixRevert(): void {
    this.store.dispatch({ type: 'revert-to-committed' });
  }

  private _openWarningsModal(): void {
    const s = this.store.state;
    if (s.schemaWarnings.length === 0) return;
    if (s.fixScreen) return;

    const currentSchema = this._currentSchemaForFixScreen();
    if (!currentSchema) return;

    this.store.dispatch({
      type: 'open-fix-screen',
      migrations: [],
      newSchema: currentSchema,
    });
  }

  // ---------------------------------------------------------------------------
  // Example operations (need extra logic beyond simple dispatch)
  // ---------------------------------------------------------------------------

  private _addExample(): void {
    const s = this.store.state;
    const newExample: DataExample = {
      id: nanoid(),
      name: `Example ${s.examples.length + 1}`,
      data: {},
    };
    this.store.dispatch({ type: 'add-example', example: newExample });
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

    if (result.compatible) {
      if (!isRootJsonSchema(schema)) {
        this._importParseError = 'Schema root must have type "object"';
        return;
      }

      const visualSchema = jsonSchemaToVisualSchema(schema);
      this.store.dispatch({
        type: 'import-visual-schema',
        schema,
        visualSchema,
        selectedFieldId: visualSchema.fields.length > 0 ? visualSchema.fields[0].id : null,
      });
    } else {
      this.store.dispatch({
        type: 'import-json-only-schema',
        schema,
      });
    }

    this._closeImportDialog();
  }

  // ---------------------------------------------------------------------------
  // Copy to clipboard
  // ---------------------------------------------------------------------------

  private async _copyJsonSchemaToClipboard(): Promise<void> {
    const s = this.store.state;
    const schema =
      s.schemaEditMode === 'json-only'
        ? s.rawJsonSchema
        : s.visualSchema.fields.length > 0
          ? visualSchemaToJsonSchema(s.visualSchema)
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
  // Event handlers
  // ---------------------------------------------------------------------------

  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this.store.isDirty) {
      e.preventDefault();
    }
  }

  private _handleKeyDown(e: KeyboardEvent): void {
    const isMod = e.metaKey || e.ctrlKey;
    if (!isMod) return;

    const s = this.store.state;

    if (s.activeTab === 'schema' && s.schemaEditMode !== 'json-only') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this.store.dispatch({ type: 'undo-schema' });
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this.store.dispatch({ type: 'redo-schema' });
      }
    } else if (s.activeTab === 'examples') {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this.store.dispatch({ type: 'undo-example' });
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this.store.dispatch({ type: 'redo-example' });
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private _currentSchemaForFixScreen(): JsonSchema | null {
    return getActiveSchema(this.store.state);
  }

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
