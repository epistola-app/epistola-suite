import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { Root } from "react-dom/client";
import { EditorProvider } from "./components/editor/EditorProvider";
import { EditorLayout } from "./components/editor/EditorLayout";
import { EvaluatorProvider } from "./context/EvaluatorContext";
import { useEditorStore } from "./store/editorStore";
import type { Template, DataExample, JsonObject } from "./types/template";
import type { JsonSchema } from "./types/schema";
import { JsonSchemaSchema } from "./types/schema";
import type { ValidationError, SchemaCompatibilityResult } from "./hooks/useDataContractDraft";
import "./index.css";

/**
 * Result of updating a single data example
 */
export interface UpdateDataExampleResult {
  success: boolean;
  example?: DataExample;
  warnings?: Record<string, ValidationError[]>;
  errors?: Record<string, ValidationError[]>;
}

/**
 * Options for mounting the template editor
 */
export interface EditorOptions {
  /** The DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial template to load (optional) */
  template?: Template;
  /** Initial data examples for testing expressions (optional) */
  dataExamples?: DataExample[];
  /** Initial data model/schema for validation (optional) */
  dataModel?: JsonObject | null;
  /** Callback when user clicks Save */
  onSave?: (template: Template) => void | Promise<void>;
  /** Callback when data examples are saved (batch) */
  onSaveDataExamples?: (
    examples: DataExample[],
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  /** Callback when a single data example is updated */
  onUpdateDataExample?: (
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate?: boolean,
  ) => Promise<UpdateDataExampleResult>;
  /** Callback when a single data example is deleted */
  onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
  /** Callback when schema is saved */
  onSaveSchema?: (
    schema: JsonSchema | null,
    forceUpdate?: boolean,
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]> }>;
  /** Callback to validate schema compatibility before saving */
  onValidateSchema?: (
    schema: JsonSchema,
    examples?: DataExample[],
  ) => Promise<SchemaCompatibilityResult>;
}

/**
 * Result of mounting the editor
 */
export interface EditorInstance {
  /** Unmount the editor and clean up */
  unmount: () => void;
  /** Get the current template state */
  getTemplate: () => Template;
  /** Set/replace the template */
  setTemplate: (template: Template) => void;
}

/**
 * Mount the template editor into a container element.
 *
 * @example
 * ```typescript
 * const editor = mountEditor({
 *   container: document.getElementById('editor-container'),
 *   template: templateData,
 *   onSave: async (template) => {
 *     await fetch('/api/templates/' + template.id, {
 *       method: 'PUT',
 *       body: JSON.stringify(template)
 *     });
 *   }
 * });
 *
 * // Later, to unmount:
 * editor.unmount();
 * ```
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  const {
    container,
    template,
    dataExamples,
    dataModel,
    onSave,
    onSaveDataExamples,
    onUpdateDataExample,
    onDeleteDataExample,
    onSaveSchema,
    onValidateSchema,
  } = options;

  // Add the root class for CSS scoping
  container.classList.add("template-editor-root");

  // Initialize store with provided template
  if (template) {
    useEditorStore.getState().setTemplate(template);
    useEditorStore.getState().markAsSaved();
  }

  // Initialize store with provided data examples
  if (dataExamples && dataExamples.length > 0) {
    useEditorStore.getState().setDataExamples(dataExamples);
    // Select the first example by default
    useEditorStore.getState().selectDataExample(dataExamples[0].id);
  }

  // Initialize store with provided schema (validated at boundary)
  if (dataModel !== undefined) {
    if (dataModel === null) {
      useEditorStore.getState().setSchema(null);
    } else {
      const result = JsonSchemaSchema.safeParse(dataModel);
      if (result.success) {
        useEditorStore.getState().setSchema(result.data);
      } else {
        console.warn("Invalid JSON Schema provided, ignoring:", result.error.message);
      }
    }
  }

  // Create React root and render
  const root: Root = createRoot(container);

  root.render(
    <StrictMode>
      <EvaluatorProvider initialType="direct">
        <EditorProvider>
          <EditorLayout
            isEmbedded={true}
            onSave={onSave}
            onSaveDataExamples={onSaveDataExamples}
            onUpdateDataExample={onUpdateDataExample}
            onDeleteDataExample={onDeleteDataExample}
            onSaveSchema={onSaveSchema}
            onValidateSchema={onValidateSchema}
          />
        </EditorProvider>
      </EvaluatorProvider>
    </StrictMode>,
  );

  return {
    unmount: () => {
      root.unmount();
      container.classList.remove("template-editor-root");
    },
    getTemplate: () => useEditorStore.getState().template,
    setTemplate: (newTemplate: Template) => {
      useEditorStore.getState().setTemplate(newTemplate);
    },
  };
}

// Re-export types for consumers
export type { Template, DataExample } from "./types/template";
export { useEditorStore, useIsDirty } from "./store/editorStore";
