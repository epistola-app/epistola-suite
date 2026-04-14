/**
 * DataContractState — Pure state management for the data contract editor.
 *
 * EventTarget-based: fires 'change' events so Lit components react.
 * No Lit dependency — testable with plain unit tests.
 *
 * Manages both schema and examples with independent dirty tracking.
 */

import type {
  DataExample,
  JsonObject,
  JsonSchema,
  SaveCallbacks,
  SaveExamplesResult,
  SaveSchemaResult,
  SchemaEditMode,
  UpdateDataExampleResult,
} from './types.js';

export class DataContractState extends EventTarget {
  private _committedSchema: JsonSchema | null;
  private _committedExamples: DataExample[];

  private _draftSchema: JsonSchema | null;
  private _draftExamples: DataExample[];

  private _schemaEditMode: SchemaEditMode = 'visual';
  private _rawJsonSchema: object | null = null;
  private _committedRawJsonSchema: object | null = null;

  private _callbacks: SaveCallbacks;

  constructor(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ) {
    super();
    this._committedSchema = structuredClone(initialSchema);
    this._draftSchema = structuredClone(initialSchema);
    this._committedExamples = structuredClone(initialExamples);
    this._draftExamples = structuredClone(initialExamples);
    this._callbacks = callbacks;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  get schema(): JsonSchema | null {
    return this._draftSchema;
  }

  get dataExamples(): DataExample[] {
    return this._draftExamples;
  }

  get schemaEditMode(): SchemaEditMode {
    return this._schemaEditMode;
  }

  get rawJsonSchema(): object | null {
    return this._rawJsonSchema;
  }

  get isSchemaDirty(): boolean {
    if (this._schemaEditMode === 'json-only') {
      return JSON.stringify(this._rawJsonSchema) !== JSON.stringify(this._committedRawJsonSchema);
    }
    return JSON.stringify(this._draftSchema) !== JSON.stringify(this._committedSchema);
  }

  get isExamplesDirty(): boolean {
    return JSON.stringify(this._draftExamples) !== JSON.stringify(this._committedExamples);
  }

  get isDirty(): boolean {
    return this.isSchemaDirty || this.isExamplesDirty;
  }

  // ---------------------------------------------------------------------------
  // Schema mutations
  // ---------------------------------------------------------------------------

  setDraftSchema(schema: JsonSchema | null): void {
    this._draftSchema = schema;
    this._fireChange();
  }

  setRawJsonSchema(schema: object | null, mode: SchemaEditMode, asCommitted = false): void {
    this._rawJsonSchema = schema ? structuredClone(schema) : null;
    this._schemaEditMode = mode;
    // Also update the draft schema for validation purposes (cast through unknown)
    this._draftSchema = schema as unknown as JsonSchema | null;
    if (asCommitted) {
      this._committedRawJsonSchema = schema ? structuredClone(schema) : null;
      this._committedSchema = structuredClone(this._draftSchema);
    }
    this._fireChange();
  }

  // ---------------------------------------------------------------------------
  // Example mutations
  // ---------------------------------------------------------------------------

  setDraftExamples(examples: DataExample[]): void {
    this._draftExamples = examples;
    this._fireChange();
  }

  addDraftExample(example: DataExample): void {
    this._draftExamples = [...this._draftExamples, example];
    this._fireChange();
  }

  updateDraftExample(id: string, updates: Partial<DataExample>): void {
    this._draftExamples = this._draftExamples.map((e) => (e.id === id ? { ...e, ...updates } : e));
    this._fireChange();
  }

  deleteDraftExample(id: string): void {
    this._draftExamples = this._draftExamples.filter((e) => e.id !== id);
    this._fireChange();
  }

  // ---------------------------------------------------------------------------
  // Save operations
  // ---------------------------------------------------------------------------

  async saveSchema(forceUpdate = false): Promise<SaveSchemaResult> {
    if (!this._callbacks.onSaveSchema) {
      return { success: false, error: 'Failed to save schema' };
    }

    try {
      const schemaToSave =
        this._schemaEditMode === 'json-only'
          ? (this._rawJsonSchema as unknown as JsonSchema | null)
          : this._draftSchema;
      const result = await this._callbacks.onSaveSchema(schemaToSave, forceUpdate);
      if (result.success) {
        this._markSchemaCommitted();
      }
      return {
        success: result.success,
        warnings: result.warnings,
        error: result.error,
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save schema',
      };
    }
  }

  async saveExamples(examplesToSave?: DataExample[]): Promise<SaveExamplesResult> {
    const examples = examplesToSave ?? this._draftExamples;

    if (!this._callbacks.onSaveDataExamples) {
      return { success: false };
    }

    try {
      const result = await this._callbacks.onSaveDataExamples(examples);
      if (result.success) {
        this._draftExamples = examples;
        this._markExamplesCommitted();
      }
      return {
        success: result.success,
        warnings: result.warnings,
        error: result.error,
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save examples',
      };
    }
  }

  async saveSingleExample(
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate = false,
  ): Promise<UpdateDataExampleResult> {
    if (!this._callbacks.onUpdateDataExample) {
      return { success: false };
    }

    try {
      const result = await this._callbacks.onUpdateDataExample(exampleId, updates, forceUpdate);
      if (result.success && result.example) {
        this._draftExamples = this._draftExamples.map((e) =>
          e.id === exampleId ? result.example! : e,
        );
        this._markExamplesCommitted();
      }
      return result;
    } catch (error) {
      return {
        success: false,
        errors: {
          _: [
            {
              path: '',
              message: error instanceof Error ? error.message : 'Failed to save example',
            },
          ],
        },
      };
    }
  }

  async deleteSingleExample(exampleId: string): Promise<{ success: boolean }> {
    if (!this._callbacks.onDeleteDataExample) {
      return { success: false };
    }

    try {
      const result = await this._callbacks.onDeleteDataExample(exampleId);
      if (result.success) {
        this._draftExamples = this._draftExamples.filter((e) => e.id !== exampleId);
        this._markExamplesCommitted();
      }
      return result;
    } catch {
      return { success: false };
    }
  }

  // ---------------------------------------------------------------------------
  // Discard
  // ---------------------------------------------------------------------------

  discardDraft(): void {
    this._draftSchema = structuredClone(this._committedSchema);
    this._draftExamples = structuredClone(this._committedExamples);
    this._fireChange();
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _markSchemaCommitted(): void {
    this._committedSchema = structuredClone(this._draftSchema);
    if (this._schemaEditMode === 'json-only') {
      this._committedRawJsonSchema = structuredClone(this._rawJsonSchema);
    }
    this._fireChange();
  }

  private _markExamplesCommitted(): void {
    this._committedExamples = structuredClone(this._draftExamples);
    this._fireChange();
  }

  private _fireChange(): void {
    this.dispatchEvent(new Event('change'));
  }
}
