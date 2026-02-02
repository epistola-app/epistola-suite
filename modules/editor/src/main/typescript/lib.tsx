import {StrictMode} from "react";
import type {Root} from "react-dom/client";
import {createRoot} from "react-dom/client";
import {EditorProvider} from "./components/editor/EditorProvider";
import {EditorLayout} from "./components/editor/EditorLayout";
import {EvaluatorProvider} from "./context/EvaluatorContext";
import {useEditorStore} from "./store/editorStore";
import type {DataExample, JsonObject, Template, ThemeSummary} from "./types/template";
import {JsonSchemaSchema} from "./types/schema";
import "./index.css";

/**
 * Options for mounting the template editor
 */
export interface EditorOptions {
  /** The DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial template to load (optional) */
  template?: Template;
  /** Initial data examples for testing expressions (optional, read-only) */
  dataExamples?: DataExample[];
  /** Initial data model/schema for validation (optional, read-only) */
  dataModel?: JsonObject | null;
  /** Available themes for selection in the editor (optional) */
  themes?: ThemeSummary[];
  /** Callback when user clicks Save */
  onSave?: (template: Template) => void | Promise<void>;
  /** Callback when user selects a different example */
  onExampleSelected?: (exampleId: string | null) => void;
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
  const { container, template, dataExamples, dataModel, themes, onSave, onExampleSelected } = options;

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

  // Initialize store with available themes
  if (themes && themes.length > 0) {
    useEditorStore.getState().setThemes(themes);
  }

  // Create React root and render
  const root: Root = createRoot(container);

  root.render(
    <StrictMode>
      <EvaluatorProvider initialType="direct">
        <EditorProvider>
          <EditorLayout isEmbedded={true} onSave={onSave} onExampleSelected={onExampleSelected} />
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
export type { Template, DataExample, ThemeSummary } from "./types/template";
export { useEditorStore, useIsDirty } from "./store/editorStore";
