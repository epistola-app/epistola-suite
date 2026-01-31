import {StrictMode} from "react";
import {createRoot} from "react-dom/client";
import {SchemaManagerApp} from "./App";
import {createSchemaManagerStore} from "./store/schemaStore";
import type {SaveCallbacks, ValidationError} from "./hooks/useDataContractDraft";
import type {JsonSchema} from "./types/schema";
import type {DataExample} from "./types/template";

// Import styles
import "./index.css";

export const SCHEMA_MANAGER_VERSION = "0.0.0";

/**
 * Options for mounting the schema manager
 */
export interface SchemaManagerOptions {
  /** Container element to mount the schema manager into */
  container: HTMLElement;

  /** Template ID for the schema */
  templateId: number;

  /** Initial JSON schema (optional) */
  initialSchema?: JsonSchema | null;

  /** Initial data examples (optional) */
  initialExamples?: DataExample[];

  /** Callback handlers for save operations */
  callbacks: SaveCallbacks;
}

/**
 * Result of a save operation
 */
export interface SaveResult {
  success: boolean;
  warnings?: Record<string, ValidationError[]>;
  error?: string;
}

/**
 * Schema manager instance returned by mountSchemaManager
 */
export interface SchemaManagerInstance {
  /** Unmount the schema manager and clean up */
  unmount: () => void;

  /** Update the schema programmatically */
  updateSchema: (schema: JsonSchema | null) => void;

  /** Update the data examples programmatically */
  updateExamples: (examples: DataExample[]) => void;

  /** Get the current schema */
  getSchema: () => JsonSchema | null;

  /** Get the current examples */
  getExamples: () => DataExample[];
}

/**
 * Mount the schema manager into a container element
 *
 * @example
 * ```typescript
 * const manager = mountSchemaManager({
 *   container: document.getElementById('schema-manager'),
 *   templateId: 123,
 *   initialSchema: mySchema,
 *   initialExamples: [{ id: '1', name: 'Example', data: {} }],
 *   callbacks: {
 *     onSaveSchema: async (schema) => {
 *       const response = await fetch('/api/schema', {
 *         method: 'PUT',
 *         body: JSON.stringify(schema)
 *       });
 *       return { success: response.ok };
 *     }
 *   }
 * });
 *
 * // Later, clean up
 * manager.unmount();
 * ```
 */
export function mountSchemaManager(options: SchemaManagerOptions): SchemaManagerInstance {
  const {
    container,
    templateId,
    initialSchema = null,
    initialExamples = [],
    callbacks,
  } = options;

  // Create store instance
  const store = createSchemaManagerStore({
    schema: initialSchema,
    dataExamples: initialExamples,
    templateId,
  });

  // Create React root
  const root = createRoot(container);

  // Render the app
  root.render(
    <StrictMode>
      <SchemaManagerApp store={store} callbacks={callbacks} />
    </StrictMode>
  );

  // Return instance API
  return {
    unmount: () => {
      root.unmount();
    },

    updateSchema: (schema: JsonSchema | null) => {
      store.getState().setSchema(schema);
    },

    updateExamples: (examples: DataExample[]) => {
      store.getState().setDataExamples(examples);
    },

    getSchema: () => {
      return store.getState().schema;
    },

    getExamples: () => {
      return store.getState().dataExamples;
    },
  };
}

// Re-export types for external use
export type { SaveCallbacks, ValidationError, JsonSchema, DataExample };
