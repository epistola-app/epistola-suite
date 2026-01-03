import { useState, useCallback, useMemo, useEffect } from "react";
import { useEditorStore } from "../store/editorStore";
import type { DataExample, JsonObject } from "../types/template";
import type { JsonSchema } from "../types/schema";

/**
 * Validation result from backend schema compatibility check
 */
export interface MigrationSuggestion {
  exampleId: string;
  exampleName: string;
  path: string;
  issue: "TYPE_MISMATCH" | "MISSING_REQUIRED" | "UNKNOWN_FIELD";
  currentValue: unknown;
  expectedType: string;
  suggestedValue: unknown;
  autoMigratable: boolean;
}

export interface ValidationError {
  path: string;
  message: string;
}

export interface SchemaCompatibilityResult {
  compatible: boolean;
  errors: ValidationError[];
  migrations: MigrationSuggestion[];
}

export interface UpdateDataExampleResult {
  success: boolean;
  example?: DataExample;
  warnings?: Record<string, ValidationError[]>;
  errors?: Record<string, ValidationError[]>;
}

export interface SaveCallbacks {
  onSaveSchema?: (
    schema: JsonSchema | null,
    forceUpdate?: boolean
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  onSaveDataExamples?: (
    examples: DataExample[]
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  onUpdateDataExample?: (
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate?: boolean
  ) => Promise<UpdateDataExampleResult>;
  onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
  onValidateSchema?: (
    schema: JsonSchema,
    examples?: DataExample[]
  ) => Promise<SchemaCompatibilityResult>;
}

export interface DataContractDraft {
  // Draft values
  schema: JsonSchema | null;
  dataExamples: DataExample[];

  // Draft state
  isDirty: boolean;
  isSchemaDirty: boolean;
  isExamplesDirty: boolean;

  // Draft operations
  setDraftSchema: (schema: JsonSchema | null) => void;
  setDraftExamples: (examples: DataExample[]) => void;
  addDraftExample: (example: DataExample) => void;
  updateDraftExample: (id: string, updates: Partial<DataExample>) => void;
  deleteDraftExample: (id: string) => void;

  // Save operations
  saveSchema: (forceUpdate?: boolean) => Promise<SaveSchemaResult>;
  saveExamples: (examplesToSave?: DataExample[]) => Promise<SaveExamplesResult>;
  saveSingleExample: (
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate?: boolean
  ) => Promise<UpdateDataExampleResult>;
  deleteSingleExample: (exampleId: string) => Promise<{ success: boolean }>;
  discardDraft: () => void;

  // Validation
  validateSchemaCompatibility: () => Promise<SchemaCompatibilityResult | null>;
}

export interface SaveSchemaResult {
  success: boolean;
  warnings?: Record<string, ValidationError[]>;
  error?: string;
}

export interface SaveExamplesResult {
  success: boolean;
  warnings?: Record<string, ValidationError[]>;
  error?: string;
}

/**
 * Hook for managing local draft state for the Data Contract Manager.
 * Implements local-first editing with explicit save to backend.
 */
export function useDataContractDraft(callbacks: SaveCallbacks): DataContractDraft {
  // Get initial values from store
  const storeSchema = useEditorStore((s) => s.schema);
  const storeExamples = useEditorStore((s) => s.dataExamples);
  const setStoreSchema = useEditorStore((s) => s.setSchema);
  const setStoreExamples = useEditorStore((s) => s.setDataExamples);

  // Local draft state
  const [draftSchema, setDraftSchema] = useState<JsonSchema | null>(storeSchema);
  const [draftExamples, setDraftExamples] = useState<DataExample[]>(storeExamples);

  // Sync draft with store when store changes (e.g., on initial load)
  useEffect(() => {
    setDraftSchema(storeSchema);
  }, [storeSchema]);

  useEffect(() => {
    setDraftExamples(storeExamples);
  }, [storeExamples]);

  // Check if schema has changed
  const isSchemaDirty = useMemo(() => {
    return JSON.stringify(draftSchema) !== JSON.stringify(storeSchema);
  }, [draftSchema, storeSchema]);

  // Check if examples have changed
  const isExamplesDirty = useMemo(() => {
    return JSON.stringify(draftExamples) !== JSON.stringify(storeExamples);
  }, [draftExamples, storeExamples]);

  // Overall dirty state
  const isDirty = isSchemaDirty || isExamplesDirty;

  // Draft example operations
  const addDraftExample = useCallback((example: DataExample) => {
    setDraftExamples((prev) => [...prev, example]);
  }, []);

  const updateDraftExample = useCallback((id: string, updates: Partial<DataExample>) => {
    setDraftExamples((prev) =>
      prev.map((e) => (e.id === id ? { ...e, ...updates } : e))
    );
  }, []);

  const deleteDraftExample = useCallback((id: string) => {
    setDraftExamples((prev) => prev.filter((e) => e.id !== id));
  }, []);

  // Validate schema compatibility with examples
  const validateSchemaCompatibility = useCallback(async (): Promise<SchemaCompatibilityResult | null> => {
    if (!callbacks.onValidateSchema || !draftSchema) {
      return null;
    }
    return callbacks.onValidateSchema(draftSchema, draftExamples);
  }, [callbacks, draftSchema, draftExamples]);

  // Save schema to backend
  const saveSchema = useCallback(
    async (forceUpdate = false): Promise<SaveSchemaResult> => {
      if (!callbacks.onSaveSchema) {
        // No callback, just update store
        setStoreSchema(draftSchema);
        return { success: true };
      }

      try {
        const result = await callbacks.onSaveSchema(draftSchema, forceUpdate);
        if (result.success) {
          // Update store with saved schema
          setStoreSchema(draftSchema);
        }
        return {
          success: result.success,
          warnings: result.warnings,
        };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : "Failed to save schema",
        };
      }
    },
    [callbacks, draftSchema, setStoreSchema]
  );

  // Save examples to backend (batch)
  // Accepts optional examples to save (to avoid stale closure issues)
  const saveExamples = useCallback(async (examplesToSave?: DataExample[]): Promise<SaveExamplesResult> => {
    const examples = examplesToSave ?? draftExamples;

    if (!callbacks.onSaveDataExamples) {
      // No callback, just update store
      setStoreExamples(examples);
      setDraftExamples(examples);
      return { success: true };
    }

    try {
      const result = await callbacks.onSaveDataExamples(examples);
      if (result.success) {
        // Update store with saved examples
        setStoreExamples(examples);
        setDraftExamples(examples);
      }
      return {
        success: result.success,
        warnings: result.warnings,
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : "Failed to save examples",
      };
    }
  }, [callbacks, draftExamples, setStoreExamples]);

  // Save a single example to backend
  const saveSingleExample = useCallback(
    async (
      exampleId: string,
      updates: { name?: string; data?: JsonObject },
      forceUpdate = false
    ): Promise<UpdateDataExampleResult> => {
      if (!callbacks.onUpdateDataExample) {
        // No callback, update store directly
        const updatedExamples = draftExamples.map((e) =>
          e.id === exampleId ? { ...e, ...updates } : e
        );
        setStoreExamples(updatedExamples);
        setDraftExamples(updatedExamples);
        const updatedExample = updatedExamples.find((e) => e.id === exampleId);
        return { success: true, example: updatedExample };
      }

      try {
        const result = await callbacks.onUpdateDataExample(exampleId, updates, forceUpdate);
        if (result.success && result.example) {
          // Update store with the saved example
          const updatedExamples = draftExamples.map((e) =>
            e.id === exampleId ? result.example! : e
          );
          setStoreExamples(updatedExamples);
          setDraftExamples(updatedExamples);
        }
        return result;
      } catch (error) {
        return {
          success: false,
          errors: { _: [{ path: "", message: error instanceof Error ? error.message : "Failed to save example" }] },
        };
      }
    },
    [callbacks, draftExamples, setStoreExamples]
  );

  // Delete a single example from backend
  const deleteSingleExample = useCallback(
    async (exampleId: string): Promise<{ success: boolean }> => {
      if (!callbacks.onDeleteDataExample) {
        // No callback, update store directly
        const updatedExamples = draftExamples.filter((e) => e.id !== exampleId);
        setStoreExamples(updatedExamples);
        setDraftExamples(updatedExamples);
        return { success: true };
      }

      try {
        const result = await callbacks.onDeleteDataExample(exampleId);
        if (result.success) {
          // Update store after successful delete
          const updatedExamples = draftExamples.filter((e) => e.id !== exampleId);
          setStoreExamples(updatedExamples);
          setDraftExamples(updatedExamples);
        }
        return result;
      } catch (error) {
        return { success: false };
      }
    },
    [callbacks, draftExamples, setStoreExamples]
  );

  // Discard all draft changes
  const discardDraft = useCallback(() => {
    setDraftSchema(storeSchema);
    setDraftExamples(storeExamples);
  }, [storeSchema, storeExamples]);

  return {
    schema: draftSchema,
    dataExamples: draftExamples,
    isDirty,
    isSchemaDirty,
    isExamplesDirty,
    setDraftSchema,
    setDraftExamples,
    addDraftExample,
    updateDraftExample,
    deleteDraftExample,
    saveSchema,
    saveExamples,
    saveSingleExample,
    deleteSingleExample,
    discardDraft,
    validateSchemaCompatibility,
  };
}
