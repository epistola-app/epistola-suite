/**
 * DataContractStore — ReactiveController that owns all editor state.
 *
 * Single source of truth for the data contract editor. Sections and the editor
 * shell read from `store.state` and dispatch commands via `store.dispatch()`.
 * The store triggers host re-renders on state changes.
 */

/* oxlint-disable eslint/no-use-before-define */

import type {
  DataExample,
  JsonObject,
  JsonSchema,
  SaveCallbacks,
  SchemaCompatibilityPreviewResult,
  ValidationError,
} from './types.js';
import { SchemaCommandHistory } from './utils/schemaCommandHistory.js';
import { SnapshotHistory } from './utils/snapshotHistory.js';
import { validateDataAgainstSchema, type SchemaValidationError } from './utils/schemaValidation.js';
import { checkSchemaCompatibility } from './utils/schemaCompatibility.js';
import { jsonSchemaToVisualSchema, visualSchemaToJsonSchema } from './utils/schemaUtils.js';
import { setNestedValue, deleteNestedValue, normalizePath } from './utils/nestedValue.js';
import { getActiveSchema, toJsonSchemaOrNull } from './utils/activeSchema.js';
import type { EditorState, FixFieldValue, StoreCommand } from './store-types.js';
import {
  createInitialState,
  findNewFieldId,
  defaultRecentUsageSummary,
} from './store/state-init.js';
import {
  isDeepEqual,
  pruneValueToSchema,
  coerceValue,
  getExpectedTypeForPath,
  isJsonObject,
} from './store/logic.js';
import { getPropertySchema, buildDefaultValue } from './store/schema-helpers.js';

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
  private static readonly _noop = (): void => {
    return;
  };
  private readonly _controller = {
    hostConnected: DataContractStore._noop,
    hostDisconnected: DataContractStore._noop,
  };
  private _state: EditorState;
  private _callbacks: SaveCallbacks = {};
  private _updateScheduled = false;

  constructor() {
    this._state = createInitialState();
  }

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
    this._state = createInitialState();
    const s = this._state;

    s.committedSchema = structuredClone(initialSchema);
    s.schema = structuredClone(initialSchema);
    s.committedExamples = structuredClone(initialExamples);
    s.examples = structuredClone(initialExamples);

    // Check compatibility
    if (initialSchema) {
      const compat = checkSchemaCompatibility(initialSchema);
      if (!compat.compatible) {
        s.rawJsonSchema = structuredClone(initialSchema);
        s.committedRawJsonSchema = structuredClone(initialSchema);
        s.schemaEditMode = 'json-only';
        s.schemaViewMode = 'json';
        s.schema = structuredClone(initialSchema);
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
    this._syncDirtyFlags();

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
    if (this._reduceNavigation(command)) return;
    if (this._reduceSchema(command)) return;
    if (this._reduceExamples(command)) return;
    if (this._reduceFixScreen(command)) return;
    if (this._reduceSaveStatus(command)) return;
  }

  private _reduceNavigation(command: StoreCommand): boolean {
    const s = this._state;

    switch (command.type) {
      case 'select-tab':
        s.activeTab = command.tab;
        return true;
      case 'select-field':
        s.selectedFieldId = command.fieldId;
        return true;
      case 'select-example':
        s.selectedExampleId = command.exampleId;
        this._clearSaveStatus();
        return true;
      case 'toggle-field-expand': {
        const newSet = new Set(s.expandedFields);
        if (newSet.has(command.fieldId)) {
          newSet.delete(command.fieldId);
        } else {
          newSet.add(command.fieldId);
        }
        s.expandedFields = newSet;
        return true;
      }
      case 'set-schema-view-mode':
        s.schemaViewMode = command.mode;
        return true;
      default:
        return false;
    }
  }

  private _reduceSchema(command: StoreCommand): boolean {
    const s = this._state;

    switch (command.type) {
      case 'set-schema':
        s.schema = command.schema;
        this._syncSchemaDirtyFlag();
        return true;
      case 'set-raw-json-schema':
        s.rawJsonSchema = command.schema ? structuredClone(command.schema) : null;
        s.schemaEditMode = command.mode;
        s.schema = toJsonSchemaOrNull(command.schema);
        if (command.asCommitted) {
          s.committedRawJsonSchema = command.schema ? structuredClone(command.schema) : null;
          s.committedSchema = structuredClone(s.schema);
        }
        this._syncSchemaDirtyFlag();
        return true;
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
        this._syncSchemaDirtyFlag();
        return true;
      case 'import-json-only-schema':
        s.schemaCommandHistory.snapshotForImport(s.visualSchema);
        s.rawJsonSchema = structuredClone(command.schema);
        s.schemaEditMode = 'json-only';
        s.schema = toJsonSchemaOrNull(command.schema);
        s.schemaViewMode = 'json';
        this._clearSaveStatus();
        this._validateAllExamples();
        this._syncSchemaDirtyFlag();
        return true;
      case 'execute-schema-command': {
        const prevFields = s.visualSchema.fields;
        s.visualSchema = s.schemaCommandHistory.execute(command.command, s.visualSchema);
        this._syncVisualSchemaToState();
        this._clearSaveStatus();

        if (command.command.type === 'addField') {
          const newFieldId = findNewFieldId(prevFields, s.visualSchema.fields);
          if (newFieldId) s.selectedFieldId = newFieldId;
        }

        if (
          command.command.type === 'deleteField' &&
          s.selectedFieldId === command.command.fieldId
        ) {
          s.selectedFieldId = s.visualSchema.fields.length > 0 ? s.visualSchema.fields[0].id : null;
        }

        this._validateAllExamples();
        this._syncSchemaDirtyFlag();
        return true;
      }
      case 'undo-schema': {
        const prev = s.schemaCommandHistory.undo(s.visualSchema);
        if (!prev) return true;

        s.visualSchema = prev;
        this._syncVisualSchemaToState();
        this._clearSaveStatus();
        this._validateAllExamples();
        this._syncSchemaDirtyFlag();
        return true;
      }
      case 'redo-schema': {
        const next = s.schemaCommandHistory.redo(s.visualSchema);
        if (!next) return true;

        s.visualSchema = next;
        this._syncVisualSchemaToState();
        this._clearSaveStatus();
        this._validateAllExamples();
        this._syncSchemaDirtyFlag();
        return true;
      }
      default:
        return false;
    }
  }

  private _reduceExamples(command: StoreCommand): boolean {
    const s = this._state;

    switch (command.type) {
      case 'add-example':
        s.examples = [...s.examples, command.example];
        s.selectedExampleId = command.example.id;
        this._clearSaveStatus();
        this._clearExampleHistory(command.example.id);
        this._validateAllExamples();
        this._syncExamplesDirtyFlag();
        return true;
      case 'delete-example': {
        const delId = command.exampleId;
        s.examples = s.examples.filter((e) => e.id !== delId);
        s.exampleHistories.delete(delId);

        if (s.selectedExampleId === delId) {
          s.selectedExampleId = s.examples.length > 0 ? s.examples[0].id : null;
        }

        this._validateAllExamples();
        this._clearSaveStatus();
        this._syncExamplesDirtyFlag();
        return true;
      }
      case 'update-example-name':
        s.examples = s.examples.map((e) => {
          if (e.id !== command.exampleId) {
            return e;
          }

          return Object.assign({}, e, { name: command.name });
        });
        this._clearSaveStatus();
        this._syncExamplesDirtyFlag();
        return true;
      case 'update-example-data': {
        const ex = s.examples.find((e) => e.id === command.exampleId);
        if (!ex) return true;

        this._getExampleHistory(command.exampleId).push(ex.data);
        const updatedData = setNestedValue(ex.data, command.path, command.value);
        s.examples = s.examples.map((e) => {
          if (e.id !== command.exampleId) {
            return e;
          }

          return Object.assign({}, e, { data: updatedData });
        });
        this._clearSaveStatus();
        this._validateAllExamples();
        this._syncExamplesDirtyFlag();
        return true;
      }
      case 'clear-example-field': {
        const ex = s.examples.find((e) => e.id === command.exampleId);
        if (!ex) return true;

        this._getExampleHistory(command.exampleId).push(ex.data);
        const updatedData = deleteNestedValue(ex.data, command.path);
        s.examples = s.examples.map((e) => {
          if (e.id !== command.exampleId) {
            return e;
          }

          return Object.assign({}, e, { data: updatedData });
        });
        this._clearSaveStatus();
        this._validateAllExamples();
        this._syncExamplesDirtyFlag();
        return true;
      }
      case 'set-examples':
        s.examples = command.examples;
        this._syncExamplesDirtyFlag();
        return true;
      case 'commit-examples':
        s.committedExamples = structuredClone(s.examples);
        this._syncExamplesDirtyFlag();
        return true;
      case 'undo-example': {
        if (!s.selectedExampleId) return true;
        const ex = s.examples.find((e) => e.id === s.selectedExampleId);
        if (!ex) return true;

        const history = this._getExampleHistory(s.selectedExampleId);
        const prevData = history.undo(ex.data);
        if (!prevData) return true;

        s.examples = s.examples.map((e) => {
          if (e.id !== s.selectedExampleId) {
            return e;
          }

          return Object.assign({}, e, { data: prevData });
        });
        this._validateAllExamples();
        this._syncExamplesDirtyFlag();
        return true;
      }
      case 'redo-example': {
        if (!s.selectedExampleId) return true;
        const ex = s.examples.find((e) => e.id === s.selectedExampleId);
        if (!ex) return true;

        const history = this._getExampleHistory(s.selectedExampleId);
        const nextData = history.redo(ex.data);
        if (!nextData) return true;

        s.examples = s.examples.map((e) => {
          if (e.id !== s.selectedExampleId) {
            return e;
          }

          return Object.assign({}, e, { data: nextData });
        });
        this._validateAllExamples();
        this._syncExamplesDirtyFlag();
        return true;
      }
      default:
        return false;
    }
  }

  // oxlint-disable-next-line eslint/complexity
  private _reduceFixScreen(command: StoreCommand): boolean {
    const s = this._state;

    switch (command.type) {
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
        return true;
      }
      case 'fix-field-change':
        if (s.fixScreen) {
          const key = `${command.exampleId}:${command.path}`;
          const existing = s.fixScreen.fields.get(key);
          const removed = existing ? existing.removed : false;
          s.fixScreen.fields.set(key, {
            value: command.value,
            removed,
          });

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
        return true;
      case 'fix-remove-field':
        if (s.fixScreen) {
          const key = `${command.exampleId}:${command.path}`;
          const existing = s.fixScreen.fields.get(key);
          const value = existing ? existing.value : null;
          s.fixScreen.fields.set(key, {
            value,
            removed: true,
          });

          const edited = s.fixScreen.editedData.get(command.exampleId);
          if (edited) {
            const dotPath = normalizePath(command.path);
            s.fixScreen.editedData.set(command.exampleId, deleteNestedValue(edited, dotPath));
          }
        }
        return true;
      case 'fix-remove-all-unknown':
        if (s.fixScreen) {
          for (const m of s.fixScreen.migrations) {
            if (m.issue === 'UNKNOWN_FIELD') {
              const key = `${m.exampleId}:${m.path}`;
              const existing = s.fixScreen.fields.get(key);
              const value = existing ? existing.value : null;
              s.fixScreen.fields.set(key, {
                value,
                removed: true,
              });
              const edited = s.fixScreen.editedData.get(m.exampleId);
              if (edited) {
                const dotPath = normalizePath(m.path);
                s.fixScreen.editedData.set(m.exampleId, deleteNestedValue(edited, dotPath));
              }
            }
          }
        }
        return true;
      case 'close-fix-screen':
        s.fixScreen = null;
        return true;
      default:
        return false;
    }
  }

  private _reduceSaveStatus(command: StoreCommand): boolean {
    const s = this._state;

    switch (command.type) {
      case 'set-saving':
        s.saveStatus = { type: 'saving' };
        return true;
      case 'set-schema-warnings':
        s.schemaWarnings = command.warnings;
        return true;
      case 'save-success':
        s.saveStatus = { type: 'success', expiresAt: Date.now() + 3000 };
        return true;
      case 'save-error':
        s.saveStatus = {
          type: 'error',
          message: command.message,
          canForceSave: command.canForceSave,
        };
        return true;
      case 'clear-save-status':
        s.saveStatus = { type: 'idle' };
        return true;
      case 'revert-to-committed': {
        s.schema = structuredClone(s.committedSchema);
        s.examples = structuredClone(s.committedExamples);

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
        this._syncDirtyFlags();
        return true;
      }
      default:
        return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Dirty tracking
  // ---------------------------------------------------------------------------

  get isSchemaDirty(): boolean {
    return this._state.schemaDirty;
  }

  get isExamplesDirty(): boolean {
    return this._state.examplesDirty;
  }

  get isDirty(): boolean {
    return this.isSchemaDirty || this.isExamplesDirty;
  }

  private _syncDirtyFlags(): void {
    this._syncSchemaDirtyFlag();
    this._syncExamplesDirtyFlag();
  }

  private _syncSchemaDirtyFlag(): void {
    const s = this._state;
    if (s.schemaEditMode === 'json-only') {
      s.schemaDirty = !isDeepEqual(s.rawJsonSchema, s.committedRawJsonSchema);
      return;
    }

    s.schemaDirty = !isDeepEqual(s.schema, s.committedSchema);
  }

  private _syncExamplesDirtyFlag(): void {
    const s = this._state;
    s.examplesDirty = !isDeepEqual(s.examples, s.committedExamples);
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

  // oxlint-disable-next-line typescript-eslint/promise-function-async
  validateSchemaCompatibility(
    schemaToValidate?: JsonSchema | null,
  ): Promise<SchemaCompatibilityPreviewResult> {
    const s = this._state;
    const schema = schemaToValidate ?? getActiveSchema(s);

    if (!schema) {
      return Promise.resolve({
        compatible: true,
        errors: [],
        migrations: [],
        recentUsage: defaultRecentUsageSummary(),
      });
    }

    const onValidateSchemaCompatibility = this._callbacks.onValidateSchemaCompatibility;
    if (!onValidateSchemaCompatibility) {
      return Promise.resolve({
        compatible: true,
        errors: [],
        migrations: [],
        recentUsage: defaultRecentUsageSummary(),
      });
    }

    return onValidateSchemaCompatibility(schema, s.examples).catch((error: unknown) => {
      const fallbackRecentUsage = defaultRecentUsageSummary();
      const message =
        error instanceof Error ? error.message : 'Failed to validate schema compatibility';
      return {
        compatible: false,
        errors: [],
        migrations: [],
        recentUsage: {
          available: false,
          window: fallbackRecentUsage.window,
          summary: fallbackRecentUsage.summary,
          samples: [],
          issues: [],
          unavailableReason: message,
        },
        error: message,
      };
    });
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

      updatedExamples.push(Object.assign({}, existing, { data: structuredClone(editedData) }));
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
      s.fixScreen = Object.assign({}, fixScreen, { errors: newErrors });
    } else {
      s.fixScreen = Object.assign({}, fixScreen, { errors: new Map<string, string>() });
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

  // Derived undo/redo state for the selected example
  get exampleCanUndo(): boolean {
    const s = this._state;
    if (!s.selectedExampleId) return false;
    const selectedHistory = s.exampleHistories.get(s.selectedExampleId);
    return selectedHistory ? selectedHistory.canUndo : false;
  }

  get exampleCanRedo(): boolean {
    const s = this._state;
    if (!s.selectedExampleId) return false;
    const selectedHistory = s.exampleHistories.get(s.selectedExampleId);
    return selectedHistory ? selectedHistory.canRedo : false;
  }

  // ---------------------------------------------------------------------------
  // Save helpers (called by orchestrator)
  // ---------------------------------------------------------------------------

  // oxlint-disable-next-line typescript-eslint/promise-function-async
  saveSchema(
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
      this._syncSchemaDirtyFlag();
      return Promise.resolve({ success: true });
    }

    const schemaToSave = getActiveSchema(s);
    return this._callbacks
      .onSaveSchema(schemaToSave, forceUpdate, examples)
      .then((result) => {
        if (result.success) {
          s.committedSchema = structuredClone(s.schema);
          if (s.schemaEditMode === 'json-only') {
            s.committedRawJsonSchema = structuredClone(s.rawJsonSchema);
          }
          this._syncSchemaDirtyFlag();
        }
        return result;
      })
      .catch((error: unknown) => {
        return {
          success: false,
          error: error instanceof Error ? error.message : 'Failed to save schema',
        };
      });
  }

  // oxlint-disable-next-line typescript-eslint/promise-function-async
  saveExamples(): Promise<{
    success: boolean;
    warnings?: Record<string, ValidationError[]>;
    error?: string;
  }> {
    const s = this._state;
    if (!this._callbacks.onSaveDataExamples) {
      s.committedExamples = structuredClone(s.examples);
      this._syncExamplesDirtyFlag();
      return Promise.resolve({ success: true });
    }

    return this._callbacks
      .onSaveDataExamples(s.examples)
      .then((result) => {
        if (result.success) {
          s.committedExamples = structuredClone(s.examples);
          this._syncExamplesDirtyFlag();
        }
        return result;
      })
      .catch((error: unknown) => {
        return {
          success: false,
          error: error instanceof Error ? error.message : 'Failed to save examples',
        };
      });
  }

  pruneExamplesForSchema(schema: JsonSchema | null): DataExample[] {
    if (!schema) return structuredClone(this._state.examples);
    return this._state.examples.map((example) => {
      const prunedData = pruneValueToSchema(example.data, schema);
      const normalizedData: JsonObject = isJsonObject(prunedData) ? prunedData : {};
      return Object.assign({}, example, { data: normalizedData });
    });
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
}
