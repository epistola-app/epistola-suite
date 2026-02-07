/**
 * TextBlockController — Stimulus controller for TipTap-based text editing.
 *
 * Creates a TipTap editor instance on connect and destroys it on disconnect.
 * Supports inline expression chips via the ExpressionChipNode extension.
 * Syncs content changes back to the headless editor.
 *
 * Stimulus values:
 * - `blockId` (String) — the block ID for content updates
 * - `content` (String) — JSON-encoded TipTap content
 *
 * Stimulus targets:
 * - `editor` — the contenteditable container element
 */

import { Controller } from '@hotwired/stimulus';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import type { JSONContent } from '@tiptap/core';
import { ExpressionChipNode } from '../extensions/expression-chip-node.js';
import { getEditor } from '../mount.js';

/** Stimulus value declarations */
interface TextBlockValues {
  blockId: string;
  content: string;
}

export class TextBlockController extends Controller {
  static targets = ['editor'];
  static values = {
    blockId: String,
    content: String,
  };

  declare readonly editorTarget: HTMLElement;
  declare readonly hasEditorTarget: boolean;
  declare blockIdValue: string;
  declare contentValue: string;

  private tiptap: Editor | null = null;
  private updating = false;

  connect(): void {
    if (!this.hasEditorTarget) return;

    const content = this.parseContent();

    this.tiptap = new Editor({
      element: this.editorTarget,
      extensions: [
        StarterKit.configure({
          // Disable code blocks and other features not needed for template text
          codeBlock: false,
          blockquote: false,
          heading: false,
          horizontalRule: false,
          bulletList: false,
          orderedList: false,
        }),
        ExpressionChipNode,
        Placeholder.configure({
          placeholder: 'Type text here...',
        }),
      ],
      content: content ?? undefined,
      onUpdate: ({ editor }) => {
        if (this.updating) return;
        this.syncContent(editor.getJSON());
      },
    });
  }

  disconnect(): void {
    this.tiptap?.destroy();
    this.tiptap = null;
  }

  /**
   * Stimulus value callback — called when `content` value changes externally.
   * Skips update if the editor has focus to prevent cursor jumps.
   */
  contentValueChanged(): void {
    if (!this.tiptap) return;
    if (this.tiptap.isFocused) return;

    const content = this.parseContent();
    if (content) {
      this.updating = true;
      this.tiptap.commands.setContent(content);
      this.updating = false;
    }
  }

  /** Parse the JSON content string from the Stimulus value. */
  private parseContent(): JSONContent | null {
    if (!this.contentValue) return null;
    try {
      return JSON.parse(this.contentValue) as JSONContent;
    } catch {
      return null;
    }
  }

  /** Sync TipTap content back to the headless editor. */
  private syncContent(json: JSONContent): void {
    const editor = getEditor();
    if (!editor) return;
    editor.updateBlock(this.blockIdValue, { content: json });
  }
}
