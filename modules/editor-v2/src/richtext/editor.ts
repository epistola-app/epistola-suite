/**
 * TipTap editor wrapper for text blocks.
 *
 * Provides a vanilla JavaScript API for creating and managing TipTap editors.
 */

import { Editor, type JSONContent } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import { ExpressionNode, type ExpressionNodeOptions } from "./expression-node.ts";

// ============================================================================
// Types
// ============================================================================

/**
 * Options for creating a rich text editor.
 */
export interface RichTextEditorOptions {
  /** Container element to mount the editor into */
  container: HTMLElement;

  /** Initial content (TipTap JSON format) */
  content?: JSONContent;

  /** Placeholder text when editor is empty */
  placeholder?: string;

  /** Whether the editor is editable */
  editable?: boolean;

  /** Callback when content changes */
  onChange?: (content: JSONContent) => void;

  /** Callback when editor gains focus */
  onFocus?: () => void;

  /** Callback when editor loses focus */
  onBlur?: () => void;

  /** Expression node options */
  expression?: ExpressionNodeOptions;
}

/**
 * Rich text editor instance.
 */
export interface RichTextEditor {
  /** Get the current content as TipTap JSON */
  getContent(): JSONContent;

  /** Set the content from TipTap JSON */
  setContent(content: JSONContent): void;

  /** Get the content as HTML string */
  getHTML(): string;

  /** Get the content as plain text */
  getText(): string;

  /** Focus the editor */
  focus(): void;

  /** Check if editor is empty */
  isEmpty(): boolean;

  /** Check if editor is editable */
  isEditable(): boolean;

  /** Set editable state */
  setEditable(editable: boolean): void;

  /** Execute a formatting command */
  toggleBold(): void;
  toggleItalic(): void;
  toggleUnderline(): void;
  toggleStrike(): void;
  toggleBulletList(): void;
  toggleOrderedList(): void;
  setHeading(level: 1 | 2 | 3): void;
  setParagraph(): void;

  /** Insert an expression */
  insertExpression(expression: string): void;

  /** Check formatting state */
  isActive(name: string, attrs?: Record<string, unknown>): boolean;

  /** Destroy the editor */
  destroy(): void;

  /** Get the underlying TipTap editor (for advanced usage) */
  getTiptapEditor(): Editor;
}

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create a rich text editor.
 *
 * @example
 * ```typescript
 * const editor = createRichTextEditor({
 *   container: document.getElementById('editor'),
 *   content: { type: 'doc', content: [] },
 *   onChange: (content) => {
 *     console.log('Content changed:', content);
 *   },
 *   expression: {
 *     evaluate: async (expr, ctx) => ({ success: true, value: ctx[expr] }),
 *     context: { name: 'John' },
 *   },
 * });
 *
 * // Toggle bold
 * editor.toggleBold();
 *
 * // Get content
 * const content = editor.getContent();
 *
 * // Cleanup
 * editor.destroy();
 * ```
 */
export function createRichTextEditor(options: RichTextEditorOptions): RichTextEditor {
  const {
    container,
    content,
    placeholder,
    editable = true,
    onChange,
    onFocus,
    onBlur,
    expression = {},
  } = options;

  // Build extensions
  const extensions = [
    StarterKit.configure({
      heading: {
        levels: [1, 2, 3],
      },
    }),
    Underline,
    ExpressionNode.configure(expression),
  ];

  // Create TipTap editor
  const editor = new Editor({
    element: container,
    extensions,
    content: content ?? { type: "doc", content: [] },
    editable,
    editorProps: {
      attributes: {
        class: "ev2-richtext-editor",
        ...(placeholder ? { "data-placeholder": placeholder } : {}),
      },
    },
    onUpdate: ({ editor }) => {
      onChange?.(editor.getJSON());
    },
    onFocus: () => {
      onFocus?.();
    },
    onBlur: () => {
      onBlur?.();
    },
  });

  return {
    getContent(): JSONContent {
      return editor.getJSON();
    },

    setContent(newContent: JSONContent): void {
      editor.commands.setContent(newContent);
    },

    getHTML(): string {
      return editor.getHTML();
    },

    getText(): string {
      return editor.getText();
    },

    focus(): void {
      editor.commands.focus();
    },

    isEmpty(): boolean {
      return editor.isEmpty;
    },

    isEditable(): boolean {
      return editor.isEditable;
    },

    setEditable(editable: boolean): void {
      editor.setEditable(editable);
    },

    toggleBold(): void {
      editor.chain().focus().toggleBold().run();
    },

    toggleItalic(): void {
      editor.chain().focus().toggleItalic().run();
    },

    toggleUnderline(): void {
      editor.chain().focus().toggleUnderline().run();
    },

    toggleStrike(): void {
      editor.chain().focus().toggleStrike().run();
    },

    toggleBulletList(): void {
      editor.chain().focus().toggleBulletList().run();
    },

    toggleOrderedList(): void {
      editor.chain().focus().toggleOrderedList().run();
    },

    setHeading(level: 1 | 2 | 3): void {
      editor.chain().focus().toggleHeading({ level }).run();
    },

    setParagraph(): void {
      editor.chain().focus().setParagraph().run();
    },

    insertExpression(expression: string): void {
      editor.commands.insertExpression(expression);
    },

    isActive(name: string, attrs?: Record<string, unknown>): boolean {
      return editor.isActive(name, attrs);
    },

    destroy(): void {
      editor.destroy();
    },

    getTiptapEditor(): Editor {
      return editor;
    },
  };
}

// ============================================================================
// Utilities
// ============================================================================

/**
 * Convert TipTap JSON content to HTML string.
 */
export function contentToHTML(content: JSONContent): string {
  // Create a temporary editor to render the content
  const tempContainer = document.createElement("div");
  const editor = new Editor({
    element: tempContainer,
    extensions: [StarterKit, Underline, ExpressionNode],
    content,
    editable: false,
  });
  const html = editor.getHTML();
  editor.destroy();
  return html;
}

/**
 * Convert TipTap JSON content to plain text.
 */
export function contentToText(content: JSONContent): string {
  const tempContainer = document.createElement("div");
  const editor = new Editor({
    element: tempContainer,
    extensions: [StarterKit],
    content,
    editable: false,
  });
  const text = editor.getText();
  editor.destroy();
  return text;
}

/**
 * Create empty document content.
 */
export function createEmptyContent(): JSONContent {
  return {
    type: "doc",
    content: [{ type: "paragraph" }],
  };
}

/**
 * Create document content from plain text.
 */
export function createTextContent(text: string): JSONContent {
  return {
    type: "doc",
    content: [
      {
        type: "paragraph",
        content: text ? [{ type: "text", text }] : [],
      },
    ],
  };
}
