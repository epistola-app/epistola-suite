/**
 * DataContractStore — ReactiveController that owns all editor state.
 *
 * Single source of truth for the data contract editor. Sections and the editor
 * shell read from `store.state` and dispatch commands via `store.dispatch()`.
 * The store triggers host re-renders on state changes.
 */

import type {
  CompatibilityMigrationSuggestion,
  DataExample,
  JsonObject,
  JsonSchema,
  JsonSchemaProperty,
  JsonValue,
  SaveCallbacks,
  SchemaField,
  SchemaCompatibilityPreviewResult,
  ValidationError,
} from './types.js';
import { SchemaCommandHistory } from './utils/schemaCommandHistory.js';
import { SnapshotHistory } from './utils/snapshotHistory.js';
import { validateDataAgainstSchema, type SchemaValidationError } from './utils/schemaValidation.js';
import { checkSchemaCompatibility } from './utils/schemaCompatibility.js';
import { jsonSchemaToVisualSchema, visualSchemaToJsonSchema } from './utils/schemaUtils.js';
import { setNestedValue, deleteNestedValue, normalizePath } from './sections/ExampleForm.js';
import type { EditorState, FixFieldValue, StoreCommand } from './store-types.js';

export type { EditorState, StoreCommand } from './store-types.js';

// =============================================================================
// ReactiveController host interface
// =============================================================================

export interface EditorHost {
  requestUpdate(): void;
  addController(controller: unknown): void;
  removeController(controller: unknown): void;
}

// =============================================================================
// DataContractStore
// =============================================================================

export class DataContractStore {
  private _host: EditorHost | null = null;
  private readonly _controller = {
    hostConnected: () => this.hostConnected(),
    hostDisconnected: () => this.hostDisconnected(),
  };
  private _state: EditorState;
  private _callbacks: SaveCallbacks = {};
  private _updateScheduled = false;

  constructor() {
    this._state = this._createInitialState();
  }

  // ---------------------------------------------------------------------------
  // ReactiveController lifecycle
  // ---------------------------------------------------------------------------

  hostConnected(): void {}

  hostDisconnected(): void {}

  // ---------------------------------------------------------------------------
  // Host management
  // ---------------------------------------------------------------------------

  setHost(host: EditorHost): void {
    if (this._host === host) {
      return;
    }

    if (this._host) {
      this._host.removeController(this._controller);
    }

    this._host = host;
    this._host.addController(this._controller);
  }

  get state(): EditorState {
    return this._state;
  }

  get callbacks(): SaveCallbacks {
    return this._callbacks;
  }

  // ---------------------------------------------------------------------------
  // Initialization
  // ---------------------------------------------------------------------------

  init(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ): void {
    this._callbacks = callbacks;
    const s = this._state;

    s.committedSchema = structuredClone(initialSchema);
    s.schema = structuredClone(initialSchema);
    s.committedExamples = structuredClone(initialExamples);
    s.examples = structuredClone(initialExamples);

    // Check compatibility
    if (initialSchema) {
      const compat = checkSchemaCompatibility(initialSchema);
      if (!compat.compatible) {
        s.rawJsonSchema = initialSchema;
        s.committedRawJsonSchema = structuredClone(initialSchema);
        s.schemaEditMode = 'json-only';
        s.schemaViewMode = 'json';
        s.schema = initialSchema;
        s.committedSchema = structuredClone(initialSchema);
      }
    }

    // Convert to VisualSchema
    s.visualSchema = jsonSchemaToVisualSchema(initialSchema);
    s.schemaCommandHistory = new SchemaCommandHistory();

    // Pre-select first field
    if (s.visualSchema.fields.length > 0) {
      s.selectedFieldId = s.visualSchema.fields[0].id;
    }

    // Pre-select first example
    if (initialExamples.length > 0) {
      s.selectedExampleId = initialExamples[0].id;
    }

    // Validate all examples
    this._validateAllExamples();

    this._requestUpdate();
  }

  // ---------------------------------------------------------------------------
  // Dispatch
  // ---------------------------------------------------------------------------

  dispatch(command: StoreCommand): void {
    this._reduce(command);
    this._requestUpdate();
  }

  // ---------------------------------------------------------------------------
  // State reducer
  // ---------------------------------------------------------------------------

  private _reduce(command: StoreCommand): void {
    const s = this._state;

    switch (command.type) {
      // --- Navigation ---
      case 'select-tab':
        s.activeTab = command.tab;
        break;
      case 'select-field':
        s.selectedFieldId = command.fieldId;
        break;
      case 'select-example':
        s.selectedExampleId = command.exampleId;
        this._clearSaveStatus();
        this._syncExampleUndoRedoState();
        break;
      case 'toggle-field-expand': {
        const newSet = new Set(s.expandedFields);
        if (newSet.has(command.fieldId)) {
          newSet.delete(command.fieldId);
        } else {
          newSet.add(command.fieldId);
        }
        s.expandedFields = newSet;
        break;
      }
      case 'set-schema-view-mode':
        s.schemaViewMode = command.mode;
        break;

      // --- Schema ---
      case 'set-schema':
        s.schema = command.schema;
        break;
      case 'set-raw-json-schema':
        s.rawJsonSchema = command.schema ? structuredClone(command.schema) : null;
        s.schemaEditMode = command.mode;
        s.schema = toJsonSchemaOrNull(command.schema);
        if (command.asCommitted) {
          s.committedRawJsonSchema = command.schema ? structuredClone(command.schema) : null;
          s.committedSchema = structuredClone(s.schema);
        }
        break;
      case 'import-visual-schema':
        s.schemaCommandHistory.snapshotForImport(s.visualSchema);
        s.visualSchema = command.visualSchema;
        s.rawJsonSchema = null;
        s.schemaEditMode = 'visual';
        s.schema = command.schema;
        s.schemaViewMode = 'visual';
        s.selectedFieldId = command.selectedFieldId;
        this._clearSaveStatus();
        this._validateAllExamples();
        break;
      case 'import-json-only-schema':
        s.schemaCommandHistory.snapshotForImport(s.visualSchema);
        s.rawJsonSchema = structuredClone(command.schema);
        s.schemaEditMode = 'json-only';
        s.schema = toJsonSchemaOrNull(command.schema);
        s.schemaViewMode = 'json';
        this._clearSaveStatus();
        this._validateAllExamples();
        break;
      case 'execute-schema-command': {
        const prevFields = s.visualSchema.fields;
        s.visualSchema = s.schemaCommandHistory.execute(command.command, s.visualSchema);
        this._syncVisualSchemaToState();
        this._clearSaveStatus();

        // Auto-select newly added field
        if (command.command.type === 'addField') {
          const newFieldId = this._findNewFieldId(prevFields, s.visualSchema.fields);
          if (newFieldId) s.selectedFieldId = newFieldId;
        }

        // Clear selection if deleted field was selected
        if (
          command.command.type === 'deleteField' &&
          s.selectedFieldId === command.command.fieldId
        ) {
          s.selectedFieldId = s.visualSchema.fields.length > 0 ? s.visualSchema.fields[0].id : null;
        }

        this._validateAllExamples();
        break;
      }
      case 'undo-schema': {
        const prev = s.schemaCommandHistory.undo(s.visualSchema);
        if (prev) {
          s.visualSchema = prev;
          this._syncVisualSchemaToState();
          this._clearSaveStatus();
          this._validateAllExamples();
        }
        break;
      }
      case 'redo-schema': {
        const next = s.schemaCommandHistory.redo(s.visualSchema);
        if (next) {
          s.visualSchema = next;
          this._syncVisualSchemaToState();
          this._clearSaveStatus();
          this._validateAllExamples();
        }
        break;
      }

      // --- Examples ---
      case 'add-example':
        s.examples = [...s.examples, command.example];
        s.selectedExampleId = command.example.id;
        this._clearSaveStatus();
        this._clearExampleHistory(command.example.id);
        this._validateAllExamples();
        this._syncExampleUndoRedoState();
        break;
      case 'delete-example': {
        const delId = command.exampleId;
        s.examples = s.examples.filter((e) => e.id !== delId);
        s.exampleHistories.delete(delId);

        if (s.selectedExampleId === delId) {
          s.selectedExampleId = s.examples.length > 0 ? s.examples[0].id : null;
        }
        this._validateAllExamples();
        this._clearSaveStatus();
        this._syncExampleUndoRedoState();
        break;
      }
      case 'update-example-name':
        s.examples = s.examples.map((e) =>
          e.id === command.exampleId ? { ...e, name: command.name } : e,
        );
        this._clearSaveStatus();
        break;
      case 'update-example-data': {
        const ex = s.examples.find((e) => e.id === command.exampleId);
        if (ex) {
          this._getExampleHistory(command.exampleId).push(ex.data);
          const updatedData = setNestedValue(ex.data, command.path, command.value);
          s.examples = s.examples.map((e) =>
            e.id === command.exampleId ? { ...e, data: updatedData } : e,
          );
          this._clearSaveStatus();
          this._validateAllExamples();
          this._syncExampleUndoRedoState();
        }
        break;
      }
      case 'clear-example-field': {
        const ex = s.examples.find((e) => e.id === command.exampleId);
        if (ex) {
          this._getExampleHistory(command.exampleId).push(ex.data);
          const updatedData = deleteNestedValue(ex.data, command.path);
          s.examples = s.examples.map((e) =>
            e.id === command.exampleId ? { ...e, data: updatedData } : e,
          );
          this._clearSaveStatus();
          this._validateAllExamples();
          this._syncExampleUndoRedoState();
        }
        break;
      }
      case 'set-examples':
        s.examples = command.examples;
        break;
      case 'commit-examples':
        s.committedExamples = structuredClone(s.examples);
        break;
      case 'undo-example': {
        if (!s.selectedExampleId) break;
        const ex = s.examples.find((e) => e.id === s.selectedExampleId);
        if (!ex) break;
        const history = this._getExampleHistory(s.selectedExampleId);
        const prevData = history.undo(ex.data);
        if (prevData) {
          s.examples = s.examples.map((e) =>
            e.id === s.selectedExampleId ? { ...e, data: prevData } : e,
          );
          this._validateAllExamples();
          this._syncExampleUndoRedoState();
        }
        break;
      }
      case 'redo-example': {
        if (!s.selectedExampleId) break;
        const ex = s.examples.find((e) => e.id === s.selectedExampleId);
        if (!ex) break;
        const history = this._getExampleHistory(s.selectedExampleId);
        const nextData = history.redo(ex.data);
        if (nextData) {
          s.examples = s.examples.map((e) =>
            e.id === s.selectedExampleId ? { ...e, data: nextData } : e,
          );
          this._validateAllExamples();
          this._syncExampleUndoRedoState();
        }
        break;
      }

      // --- Fix screen ---
      case 'open-fix-screen': {
        const fields = new Map<string, FixFieldValue>();
        const editedData = new Map<string, JsonObject>();
        for (const m of command.migrations) {
          fields.set(`${m.exampleId}:${m.path}`, {
            value: m.currentValue,
            removed: false,
          });
          if (!editedData.has(m.exampleId)) {
            const ex = s.examples.find((e) => e.id === m.exampleId);
            if (ex) {
              editedData.set(m.exampleId, structuredClone(ex.data));
            }
          }
        }

        // Reset mismatched/missing fields to schema-appropriate defaults
        for (const m of command.migrations) {
          if (m.issue === 'UNKNOWN_FIELD') continue;
          const data = editedData.get(m.exampleId);
          if (!data) continue;
          const dotPath = normalizePath(m.path);
          const propSchema = getPropertySchema(command.newSchema, dotPath);
          if (!propSchema) continue;
          editedData.set(m.exampleId, setNestedValue(data, dotPath, buildDefaultValue(propSchema)));
        }

        s.fixScreen = {
          migrations: command.migrations,
          newSchema: command.newSchema,
          fields,
          errors: new Map(),
          editedData,
        };
        break;
      }
      case 'fix-field-change':
        if (s.fixScreen) {
          const key = `${command.exampleId}:${command.path}`;
          const existing = s.fixScreen.fields.get(key);
          s.fixScreen.fields.set(key, {
            value: command.value,
            removed: existing?.removed ?? false,
          });
          // Update the full edited copy so nested edits (add/remove array items) work
          const edited = s.fixScreen.editedData.get(command.exampleId);
          if (edited) {
            const dotPath = normalizePath(command.path);
            const expectedType = getExpectedTypeForPath(
              s.fixScreen.migrations,
              command.exampleId,
              command.path,
            );
            const coerced = coerceValue(command.value, expectedType);
            s.fixScreen.editedData.set(command.exampleId, setNestedValue(edited, dotPath, coerced));
          }
        }
        break;
      case 'fix-remove-field':
        if (s.fixScreen) {
          const key = `${command.exampleId}:${command.path}`;
          const existing = s.fixScreen.fields.get(key);
          s.fixScreen.fields.set(key, {
            value: existing?.value ?? null,
            removed: true,
          });
          // Also remove from editedData
          const edited = s.fixScreen.editedData.get(command.exampleId);
          if (edited) {
            const dotPath = normalizePath(command.path);
            s.fixScreen.editedData.set(command.exampleId, deleteNestedValue(edited, dotPath));
          }
        }
        break;
      case 'fix-remove-all-unknown':
        if (s.fixScreen) {
          for (const m of s.fixScreen.migrations) {
            if (m.issue === 'UNKNOWN_FIELD') {
              const key = `${m.exampleId}:${m.path}`;
              const existing = s.fixScreen.fields.get(key);
              s.fixScreen.fields.set(key, {
                value: existing?.value ?? null,
                removed: true,
              });
              // Remove from editedData
              const edited = s.fixScreen.editedData.get(m.exampleId);
              if (edited) {
                const dotPath = normalizePath(m.path);
                s.fixScreen.editedData.set(m.exampleId, deleteNestedValue(edited, dotPath));
              }
            }
          }
        }
        break;
      case 'close-fix-screen':
        s.fixScreen = null;
        break;

      // --- Save status ---
      case 'set-saving':
        s.saveStatus = { type: 'saving' };
        break;
      case 'set-schema-warnings':
        s.schemaWarnings = command.warnings;
        break;
      case 'save-success':
        s.saveStatus = { type: 'success', expiresAt: Date.now() + 3000 };
        break;
      case 'save-error':
        s.saveStatus = {
          type: 'error',
          message: command.message,
          canForceSave: command.canForceSave,
        };
        break;
      case 'clear-save-status':
        s.saveStatus = { type: 'idle' };
        break;
      case 'revert-to-committed': {
        s.schema = structuredClone(s.committedSchema);
        s.examples = structuredClone(s.committedExamples);

        // Restore schema edit mode/view from committed state
        if (s.committedRawJsonSchema !== null) {
          s.rawJsonSchema = structuredClone(s.committedRawJsonSchema);
          s.schemaEditMode = 'json-only';
          s.schemaViewMode = 'json';
        } else {
          s.rawJsonSchema = null;
          s.schemaEditMode = 'visual';
          s.schemaViewMode = 'visual';
        }

        s.visualSchema = jsonSchemaToVisualSchema(s.schema);
        s.schemaCommandHistory.clear();
        s.exampleHistories.clear();
        s.fixScreen = null;
        s.schemaWarnings = [];
        s.saveStatus = { type: 'idle' };

        s.selectedFieldId = s.visualSchema.fields.length > 0 ? s.visualSchema.fields[0].id : null;
        if (!s.examples.some((e) => e.id === s.selectedExampleId)) {
          s.selectedExampleId = s.examples.length > 0 ? s.examples[0].id : null;
        }

        this._validateAllExamples();
        break;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty tracking
  // ---------------------------------------------------------------------------

  get isSchemaDirty(): boolean {
    // NOTE: This uses structural JSON comparison for correctness and simplicity.
    // For very large schemas, this may be expensive; if profiling shows hotspots,
    // prefer memoized dirty checks tied to reducer revision counters.
    const s = this._state;
    if (s.schemaEditMode === 'json-only') {
      return JSON.stringify(s.rawJsonSchema) !== JSON.stringify(s.committedRawJsonSchema);
    }
    return JSON.stringify(s.schema) !== JSON.stringify(s.committedSchema);
  }

  get isExamplesDirty(): boolean {
    const s = this._state;
    return JSON.stringify(s.examples) !== JSON.stringify(s.committedExamples);
  }

  get isDirty(): boolean {
    return this.isSchemaDirty || this.isExamplesDirty;
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  validateAllExamples(): void {
    this._validateAllExamples();
  }

  private _validateAllExamples(): void {
    const s = this._state;
    const newErrors = new Map<string, SchemaValidationError[]>();

    if (s.schema) {
      for (const example of s.examples) {
        const result = validateDataAgainstSchema(example.data, s.schema);
        newErrors.set(example.id, result.errors);
      }
    }

    s.validationErrors = newErrors;
  }

  // ---------------------------------------------------------------------------
  // Compatibility
  // ---------------------------------------------------------------------------

  async validateSchemaCompatibility(
    schemaToValidate?: JsonSchema | null,
  ): Promise<SchemaCompatibilityPreviewResult> {
    const s = this._state;
    const schema = schemaToValidate ?? getActiveSchema(s);

    if (!schema) {
      return {
        compatible: true,
        errors: [],
        migrations: [],
        recentUsage: { available: true, checkedCount: 0, incompatibleCount: 0, issues: [] },
      };
    }

    if (!this._callbacks.onValidateSchemaCompatibility) {
      return {
        compatible: true,
        errors: [],
        migrations: [],
        recentUsage: { available: true, checkedCount: 0, incompatibleCount: 0, issues: [] },
      };
    }

    try {
      return await this._callbacks.onValidateSchemaCompatibility(schema, s.examples);
    } catch (error) {
      return {
        compatible: false,
        errors: [],
        migrations: [],
        recentUsage: {
          available: false,
          checkedCount: 0,
          incompatibleCount: 0,
          issues: [],
          unavailableReason:
            error instanceof Error ? error.message : 'Failed to validate schema compatibility',
        },
        error: error instanceof Error ? error.message : 'Failed to validate schema compatibility',
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Fix screen helpers
  // ---------------------------------------------------------------------------

  buildFixedExamples(): DataExample[] | null {
    const s = this._state;
    if (!s.fixScreen) return null;

    const fixScreen = s.fixScreen;
    const updatedExamples: DataExample[] = [];

    for (const [exampleId, editedData] of fixScreen.editedData) {
      const existing = s.examples.find((e) => e.id === exampleId);
      if (!existing) continue;

      updatedExamples.push({
        ...existing,
        data: structuredClone(editedData),
      });
    }

    return updatedExamples;
  }

  validateFixScreenFields(): boolean {
    const s = this._state;
    if (!s.fixScreen) return true;

    const fixScreen = s.fixScreen;
    const updatedExamples = this.buildFixedExamples();
    if (!updatedExamples) return true;

    let hasErrors = false;
    const newErrors = new Map<string, string>();

    for (const updated of updatedExamples) {
      const validation = validateDataAgainstSchema(updated.data, fixScreen.newSchema);
      for (const err of validation.errors) {
        const errPath = err.path.replace(/^\$\./, '');
        for (const m of fixScreen.migrations) {
          if (m.exampleId === updated.id) {
            const fieldPath = normalizePath(m.path);
            if (errPath === fieldPath) {
              const key = `${m.exampleId}:${m.path}`;
              newErrors.set(key, err.message);
              hasErrors = true;
            }
          }
        }
      }
    }

    // Update errors in fix screen state
    if (hasErrors) {
      // We need to create a new fixScreen object to trigger re-render
      s.fixScreen = { ...fixScreen, errors: newErrors };
    } else {
      s.fixScreen = { ...fixScreen, errors: new Map() };
    }

    return !hasErrors;
  }

  // ---------------------------------------------------------------------------
  // Schema helpers
  // ---------------------------------------------------------------------------

  syncVisualSchemaToState(): void {
    this._syncVisualSchemaToState();
  }

  private _syncVisualSchemaToState(): void {
    const s = this._state;
    if (s.schemaEditMode === 'json-only') return;

    if (s.visualSchema.fields.length > 0) {
      s.schema = visualSchemaToJsonSchema(s.visualSchema);
    } else {
      s.schema = null;
    }
  }

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

  // ---------------------------------------------------------------------------
  // Example undo/redo helpers
  // ---------------------------------------------------------------------------

  private _getExampleHistory(exampleId: string): SnapshotHistory<JsonObject> {
    const s = this._state;
    let history = s.exampleHistories.get(exampleId);
    if (!history) {
      history = new SnapshotHistory<JsonObject>();
      s.exampleHistories.set(exampleId, history);
    }
    return history;
  }

  private _clearExampleHistory(exampleId: string): void {
    this._getExampleHistory(exampleId).clear();
  }

  private _syncExampleUndoRedoState(): void {
    // The undo/redo state is derived from the selected example's history.
    // The store exposes canUndo/canRedo via the state's exampleHistories.
    // The editor reads this when building the UI state.
  }

  // Derived undo/redo state for the selected example
  get exampleCanUndo(): boolean {
    const s = this._state;
    if (!s.selectedExampleId) return false;
    return s.exampleHistories.get(s.selectedExampleId)?.canUndo ?? false;
  }

  get exampleCanRedo(): boolean {
    const s = this._state;
    if (!s.selectedExampleId) return false;
    return s.exampleHistories.get(s.selectedExampleId)?.canRedo ?? false;
  }

  // ---------------------------------------------------------------------------
  // Save helpers (called by orchestrator)
  // ---------------------------------------------------------------------------

  async saveSchema(
    forceUpdate = false,
    examples?: DataExample[],
  ): Promise<{
    success: boolean;
    warnings?: Record<string, ValidationError[]>;
    error?: string;
  }> {
    const s = this._state;
    if (!this._callbacks.onSaveSchema) {
      s.committedSchema = structuredClone(s.schema);
      if (s.schemaEditMode === 'json-only') {
        s.committedRawJsonSchema = structuredClone(s.rawJsonSchema);
      }
      return { success: true };
    }

    try {
      const schemaToSave = getActiveSchema(s);
      const result = await this._callbacks.onSaveSchema(schemaToSave, forceUpdate, examples);
      if (result.success) {
        s.committedSchema = structuredClone(s.schema);
        if (s.schemaEditMode === 'json-only') {
          s.committedRawJsonSchema = structuredClone(s.rawJsonSchema);
        }
      }
      return result;
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save schema',
      };
    }
  }

  async saveExamples(): Promise<{
    success: boolean;
    warnings?: Record<string, ValidationError[]>;
    error?: string;
  }> {
    const s = this._state;
    if (!this._callbacks.onSaveDataExamples) {
      s.committedExamples = structuredClone(s.examples);
      return { success: true };
    }

    try {
      const result = await this._callbacks.onSaveDataExamples(s.examples);
      if (result.success) {
        s.committedExamples = structuredClone(s.examples);
      }
      return result;
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save examples',
      };
    }
  }

  pruneExamplesForSchema(schema: JsonSchema | null): DataExample[] {
    if (!schema) return structuredClone(this._state.examples);
    return this._state.examples.map((example) => ({
      ...example,
      data: (pruneValueToSchema(example.data, schema) as JsonObject) ?? {},
    }));
  }

  markSchemaCommandHistoryClear(): void {
    this._state.schemaCommandHistory.clear();
  }

  clearExampleHistories(): void {
    this._state.exampleHistories.clear();
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _clearSaveStatus(): void {
    this._state.saveStatus = { type: 'idle' };
  }

  private _requestUpdate(): void {
    if (!this._updateScheduled && this._host) {
      this._updateScheduled = true;
      const host = this._host;
      queueMicrotask(() => {
        this._updateScheduled = false;
        host.requestUpdate();
      });
    }
  }

  private _createInitialState(): EditorState {
    return {
      schema: null,
      committedSchema: null,
      examples: [],
      committedExamples: [],
      visualSchema: { fields: [] },
      schemaEditMode: 'visual',
      rawJsonSchema: null,
      committedRawJsonSchema: null,
      schemaCommandHistory: new SchemaCommandHistory(),
      activeTab: 'schema',
      selectedFieldId: null,
      selectedExampleId: null,
      expandedFields: new Set(),
      schemaViewMode: 'visual',
      fixScreen: null,
      saveStatus: { type: 'idle' },
      validationErrors: new Map(),
      schemaWarnings: [],
      exampleHistories: new Map(),
    };
  }
}

// ---------------------------------------------------------------------------
// Fix screen helpers
// ---------------------------------------------------------------------------

function getExpectedTypeForPath(
  migrations: CompatibilityMigrationSuggestion[],
  exampleId: string,
  path: string,
): string | null {
  const m = migrations.find((m) => m.exampleId === exampleId && m.path === path);
  return m?.expectedType ?? null;
}

function coerceValue(value: JsonValue | null, expectedType: string | null): JsonValue | null {
  if (value === null || value === undefined || !expectedType) return value;

  switch (expectedType) {
    case 'number':
    case 'integer': {
      const num = Number(value);
      return Number.isNaN(num) ? value : expectedType === 'integer' ? Math.trunc(num) : num;
    }
    case 'boolean': {
      if (typeof value === 'boolean') return value;
      if (value === 'true') return true;
      if (value === 'false') return false;
      return value;
    }
    default:
      return value;
  }
}

function pruneValueToSchema(value: JsonValue, schema: JsonSchema | JsonSchemaProperty): JsonValue {
  if (value === null || value === undefined) return value;

  const schemaType = Array.isArray(schema.type) ? schema.type[0] : schema.type;

  if (schemaType === 'object' && typeof value === 'object' && !Array.isArray(value)) {
    const obj = value as JsonObject;
    const properties = schema.properties ?? {};
    const result: JsonObject = {};

    for (const [key, propSchema] of Object.entries(properties)) {
      if (key in obj) {
        result[key] = pruneValueToSchema(obj[key], propSchema);
      }
    }

    return result;
  }

  if (schemaType === 'array' && Array.isArray(value)) {
    const itemSchema = 'items' in schema ? schema.items : undefined;
    if (!itemSchema) return value;
    return value.map((item) => pruneValueToSchema(item, itemSchema));
  }

  return value;
}

function toJsonSchemaOrNull(schema: object | null): JsonSchema | null {
  return isRootJsonSchema(schema) ? schema : null;
}

function getActiveSchema(state: EditorState): JsonSchema | null {
  if (state.schemaEditMode === 'json-only') {
    return toJsonSchemaOrNull(state.rawJsonSchema);
  }

  return state.schema;
}

function isRootJsonSchema(value: unknown): value is JsonSchema {
  return isRecord(value) && value.type === 'object';
}

/**
 * Look up a property's JSON Schema by dot-separated path.
 */
function getPropertySchema(schema: JsonSchema, dotPath: string): JsonSchemaProperty | null {
  const segments = dotPath.split('.');
  let current: unknown = schema;

  for (let i = 0; i < segments.length; i++) {
    if (!isRecord(current)) {
      return null;
    }

    const segment = segments[i];
    const props = getProperties(current);

    if (i === segments.length - 1) {
      const candidate = props?.[segment];
      if (isJsonSchemaProperty(candidate)) {
        return candidate;
      }

      // Numeric segment: return the array items schema
      if (/^\d+$/.test(segment)) {
        const items = getItems(current);
        if (isJsonSchemaProperty(items)) {
          return items;
        }
      }

      return null;
    }

    // Numeric segment: skip through array items schema
    if (/^\d+$/.test(segment)) {
      const items = getItems(current);
      if (!items) {
        return null;
      }

      current = items;
      continue;
    }

    const childFromProperties = props?.[segment];
    if (isRecord(childFromProperties)) {
      current = childFromProperties;
      continue;
    }

    const items = getItems(current);
    if (!items) {
      return null;
    }

    const nestedProps = getProperties(items);
    const childFromItems = nestedProps?.[segment];
    if (isRecord(childFromItems)) {
      current = childFromItems;
    } else {
      return null;
    }
  }

  return isJsonSchemaProperty(current) ? current : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function getProperties(node: Record<string, unknown>): Record<string, unknown> | null {
  const properties = node.properties;
  return isRecord(properties) ? properties : null;
}

function getItems(node: Record<string, unknown>): Record<string, unknown> | null {
  const items = node.items;
  return isRecord(items) ? items : null;
}

function isJsonSchemaProperty(value: unknown): value is JsonSchemaProperty {
  if (!isRecord(value)) {
    return false;
  }

  const typeValue = value.type;
  if (typeof typeValue === 'string') {
    return true;
  }

  return Array.isArray(typeValue) && typeValue.every((item) => typeof item === 'string');
}

/**
 * Build a default value from a JSON Schema property definition.
 */
function buildDefaultValue(propSchema: JsonSchemaProperty): JsonValue {
  const rawType = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type;
  const type = rawType === 'string' && propSchema.format === 'date' ? 'date' : rawType;

  switch (type) {
    case 'string':
    case 'date':
      return '';
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'object': {
      if (!propSchema.properties) return {};
      const obj: Record<string, JsonValue> = {};
      for (const [key, child] of Object.entries(propSchema.properties)) {
        obj[key] = buildDefaultValue(child);
      }
      return obj;
    }
    case 'array': {
      if (propSchema.items) {
        return [buildDefaultValue(propSchema.items)];
      }
      return [];
    }
    default:
      return '';
  }
}
