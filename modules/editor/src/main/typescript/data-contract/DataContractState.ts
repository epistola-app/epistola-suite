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
  UpdateDataExampleResult,
} from './types.js'

export class DataContractState extends EventTarget {
  private _committedSchema: JsonSchema | null
  private _committedExamples: DataExample[]

  private _draftSchema: JsonSchema | null
  private _draftExamples: DataExample[]

  private _callbacks: SaveCallbacks

  constructor(
    initialSchema: JsonSchema | null,
    initialExamples: DataExample[],
    callbacks: SaveCallbacks,
  ) {
    super()
    this._committedSchema = structuredClone(initialSchema)
    this._draftSchema = structuredClone(initialSchema)
    this._committedExamples = structuredClone(initialExamples)
    this._draftExamples = structuredClone(initialExamples)
    this._callbacks = callbacks
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  get schema(): JsonSchema | null {
    return this._draftSchema
  }

  get dataExamples(): DataExample[] {
    return this._draftExamples
  }

  get isSchemaDirty(): boolean {
    return JSON.stringify(this._draftSchema) !== JSON.stringify(this._committedSchema)
  }

  get isExamplesDirty(): boolean {
    return JSON.stringify(this._draftExamples) !== JSON.stringify(this._committedExamples)
  }

  get isDirty(): boolean {
    return this.isSchemaDirty || this.isExamplesDirty
  }

  // ---------------------------------------------------------------------------
  // Schema mutations
  // ---------------------------------------------------------------------------

  setDraftSchema(schema: JsonSchema | null): void {
    this._draftSchema = schema
    this._fireChange()
  }

  // ---------------------------------------------------------------------------
  // Example mutations
  // ---------------------------------------------------------------------------

  setDraftExamples(examples: DataExample[]): void {
    this._draftExamples = examples
    this._fireChange()
  }

  addDraftExample(example: DataExample): void {
    this._draftExamples = [...this._draftExamples, example]
    this._fireChange()
  }

  updateDraftExample(id: string, updates: Partial<DataExample>): void {
    this._draftExamples = this._draftExamples.map((e) =>
      e.id === id ? { ...e, ...updates } : e,
    )
    this._fireChange()
  }

  deleteDraftExample(id: string): void {
    this._draftExamples = this._draftExamples.filter((e) => e.id !== id)
    this._fireChange()
  }

  // ---------------------------------------------------------------------------
  // Save operations
  // ---------------------------------------------------------------------------

  async saveSchema(forceUpdate = false): Promise<SaveSchemaResult> {
    if (!this._callbacks.onSaveSchema) {
      this._markSchemaCommitted()
      return { success: true }
    }

    try {
      const result = await this._callbacks.onSaveSchema(this._draftSchema, forceUpdate)
      if (result.success) {
        this._markSchemaCommitted()
      }
      return {
        success: result.success,
        warnings: result.warnings,
        error: result.error,
      }
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save schema',
      }
    }
  }

  async saveExamples(examplesToSave?: DataExample[]): Promise<SaveExamplesResult> {
    const examples = examplesToSave ?? this._draftExamples

    if (!this._callbacks.onSaveDataExamples) {
      this._draftExamples = examples
      this._markExamplesCommitted()
      return { success: true }
    }

    try {
      const result = await this._callbacks.onSaveDataExamples(examples)
      if (result.success) {
        this._draftExamples = examples
        this._markExamplesCommitted()
      }
      return {
        success: result.success,
        warnings: result.warnings,
        error: result.error,
      }
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : 'Failed to save examples',
      }
    }
  }

  async saveSingleExample(
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate = false,
  ): Promise<UpdateDataExampleResult> {
    if (!this._callbacks.onUpdateDataExample) {
      const updatedExamples = this._draftExamples.map((e) =>
        e.id === exampleId ? { ...e, ...updates } : e,
      )
      this._draftExamples = updatedExamples
      this._markExamplesCommitted()
      const updatedExample = updatedExamples.find((e) => e.id === exampleId)
      return { success: true, example: updatedExample }
    }

    try {
      const result = await this._callbacks.onUpdateDataExample(exampleId, updates, forceUpdate)
      if (result.success && result.example) {
        this._draftExamples = this._draftExamples.map((e) =>
          e.id === exampleId ? result.example! : e,
        )
        this._markExamplesCommitted()
      }
      return result
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
      }
    }
  }

  async deleteSingleExample(exampleId: string): Promise<{ success: boolean }> {
    if (!this._callbacks.onDeleteDataExample) {
      this._draftExamples = this._draftExamples.filter((e) => e.id !== exampleId)
      this._markExamplesCommitted()
      return { success: true }
    }

    try {
      const result = await this._callbacks.onDeleteDataExample(exampleId)
      if (result.success) {
        this._draftExamples = this._draftExamples.filter((e) => e.id !== exampleId)
        this._markExamplesCommitted()
      }
      return result
    } catch {
      return { success: false }
    }
  }

  // ---------------------------------------------------------------------------
  // Discard
  // ---------------------------------------------------------------------------

  discardDraft(): void {
    this._draftSchema = structuredClone(this._committedSchema)
    this._draftExamples = structuredClone(this._committedExamples)
    this._fireChange()
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _markSchemaCommitted(): void {
    this._committedSchema = structuredClone(this._draftSchema)
    this._fireChange()
  }

  private _markExamplesCommitted(): void {
    this._committedExamples = structuredClone(this._draftExamples)
    this._fireChange()
  }

  private _fireChange(): void {
    this.dispatchEvent(new Event('change'))
  }
}
