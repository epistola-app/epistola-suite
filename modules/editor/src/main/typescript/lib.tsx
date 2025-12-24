import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { Root } from "react-dom/client";
import { EditorProvider } from "./components/editor/EditorProvider";
import { EditorLayout } from "./components/editor/EditorLayout";
import { EvaluatorProvider } from "./context/EvaluatorContext";
import { useEditorStore } from "./store/editorStore";
import type { Template } from "./types/template";
import "./index.css";
import App from "./App";

/**
 * Options for mounting the template editor
 */
export interface EditorOptions {
  /** The DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial template to load (optional) */
  template?: Template;
  /** Callback when user clicks Save */
  onSave?: (template: Template) => void;
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
  const { container, template, onSave } = options;

  // Add the root class for CSS scoping
  container.classList.add("template-editor-root");

  // Initialize store with provided template
  if (template) {
    useEditorStore.getState().setTemplate(template);
  }

  // Create React root and render
  const root: Root = createRoot(container);

  root.render(
    <StrictMode>
      <EvaluatorProvider initialType="direct">
        <EditorProvider>
          <EditorLayout isEmbedded={true} onSave={onSave} />
        </EditorProvider>
      </EvaluatorProvider>
      <App />
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
export type { Template } from "./types/template";
export { useEditorStore } from "./store/editorStore";
