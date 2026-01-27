import { create } from "zustand";
import type { JsonSchema } from "../types/schema";
import type { DataExample } from "../types/template";

/**
 * State for the schema manager
 */
interface SchemaManagerState {
  schema: JsonSchema | null;
  dataExamples: DataExample[];
  templateId: number;
}

/**
 * Actions for the schema manager
 */
interface SchemaManagerActions {
  setSchema: (schema: JsonSchema | null) => void;
  setDataExamples: (examples: DataExample[]) => void;
  setTemplateId: (id: number) => void;
}

type SchemaManagerStore = SchemaManagerState & SchemaManagerActions;

/**
 * Create a schema manager store instance
 *
 * Note: We don't use Immer middleware here because JsonSchema is a deeply
 * recursive type that causes TypeScript's type instantiation to fail.
 * Since we're replacing the entire schema/examples objects anyway, Immer
 * isn't needed.
 */
export function createSchemaManagerStore(initialState: {
  schema: JsonSchema | null;
  dataExamples: DataExample[];
  templateId: number;
}) {
  return create<SchemaManagerStore>()((set) => ({
    // Initial state
    schema: initialState.schema,
    dataExamples: initialState.dataExamples,
    templateId: initialState.templateId,

    // Actions
    setSchema: (schema: JsonSchema | null) =>
      set(() => ({
        schema,
      })),

    setDataExamples: (examples: DataExample[]) =>
      set(() => ({
        dataExamples: examples,
      })),

    setTemplateId: (id: number) =>
      set(() => ({
        templateId: id,
      })),
  }));
}

export type { SchemaManagerState, SchemaManagerActions, SchemaManagerStore };
