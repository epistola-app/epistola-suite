/**
 * EpistolaRichTextInput — Minimal ProseMirror editor for rich-text parameter values.
 *
 * Used in the data-contract test-data form (and array-of-richText rows) to let
 * the user author values for richText fields. Mounts ProseMirror against the
 * subset schema chosen by the `mode` prop:
 *   - `mode="inline"` → richTextInlineSchema (single paragraph, no lists)
 *   - `mode="block"`  → richTextBlockSchema  (paragraphs, lists, marks)
 *
 * Lifecycle:
 *   - `value` prop is the ProseMirror JSON doc
 *   - User edits dispatch a `rich-text-change` CustomEvent with detail.value
 *   - External `value` updates re-create the editor state when the JSON differs
 *   - Switching `mode` recreates the EditorView so the new schema/plugins apply
 */

import { LitElement, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { EditorState, type Plugin } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { Node as ProsemirrorNode, type Schema } from 'prosemirror-model';
import { history, undo, redo } from 'prosemirror-history';
import { keymap } from 'prosemirror-keymap';
import { baseKeymap, toggleMark, chainCommands } from 'prosemirror-commands';
import { splitListItem, liftListItem, sinkListItem } from 'prosemirror-schema-list';
import { dropCursor } from 'prosemirror-dropcursor';
import { gapCursor } from 'prosemirror-gapcursor';
import { bubbleMenuPlugin } from '../../prosemirror/bubble-menu.js';
import {
  richTextBlockSchema,
  EMPTY_RICH_TEXT_BLOCK_DOC,
} from '../../prosemirror/richTextBlockSchema.js';
import {
  richTextInlineSchema,
  EMPTY_RICH_TEXT_INLINE_DOC,
} from '../../prosemirror/richTextInlineSchema.js';
import type { JsonObject, JsonValue } from '../types.js';

export type RichTextInputMode = 'inline' | 'block';

/**
 * Plugin set for a rich-text value input. Exported for tests: the capability
 * surface (lists, marks, menu) must stay derivable from the mode + schema.
 */
export function buildRichTextInputPlugins(
  mode: RichTextInputMode,
  schema: Schema,
  isEditable: () => boolean = () => true,
): Plugin[] {
  const marks = schema.marks;
  const baseBindings: Record<string, ReturnType<typeof toggleMark>> = {};
  if (marks.strong) baseBindings['Mod-b'] = toggleMark(marks.strong);
  if (marks.em) baseBindings['Mod-i'] = toggleMark(marks.em);
  if (marks.underline) baseBindings['Mod-u'] = toggleMark(marks.underline);

  const historyKeys = {
    'Mod-z': undo,
    'Shift-Mod-z': redo,
  };

  if (mode === 'block') {
    const listItem = schema.nodes.list_item;
    return [
      history(),
      keymap({
        ...historyKeys,
        ...baseBindings,
        // Lists: Enter splits, Shift-Tab lifts, Tab sinks.
        Enter: chainCommands(splitListItem(listItem), baseKeymap.Enter),
        'Shift-Tab': liftListItem(listItem),
        Tab: sinkListItem(listItem),
      }),
      keymap(baseKeymap),
      dropCursor(),
      gapCursor(),
      // Schema-adaptive: with the value subset schema this renders marks +
      // list buttons only (no heading/expression nodes to offer).
      bubbleMenuPlugin(schema, { isEditable }),
    ];
  }

  // Inline mode: no list keymaps, no Enter splitting (the schema only allows
  // a single paragraph anyway). Shift-Enter inserts a hard break; Enter is
  // a no-op (swallowed) so the user can't escape the single-paragraph shape.
  const hardBreak = schema.nodes.hard_break;
  const insertHardBreak = (
    state: EditorState,
    dispatch?: (tr: ReturnType<EditorState['tr']['replaceSelectionWith']>) => void,
  ) => {
    if (dispatch) {
      dispatch(state.tr.replaceSelectionWith(hardBreak.create()).scrollIntoView());
    }
    return true;
  };
  const swallowEnter = () => true;
  return [
    history(),
    keymap({
      ...historyKeys,
      ...baseBindings,
      'Shift-Enter': insertHardBreak,
      Enter: swallowEnter,
    }),
    keymap(baseKeymap),
    dropCursor(),
    gapCursor(),
    // Inline schema exposes marks only, so the shared menu renders formatting
    // controls without any block/list actions.
    bubbleMenuPlugin(schema, { isEditable }),
  ];
}

@customElement('epistola-rich-text-input')
export class EpistolaRichTextInput extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) value: JsonValue | null = null;
  @property({ type: Boolean }) readOnly = false;
  @property({ type: String }) placeholder = '';
  @property({ type: String }) mode: RichTextInputMode = 'block';

  /** Accept programmatic focus (e.g. from a label's for attribute) and forward to ProseMirror. */
  override focus(): void {
    this._view?.focus();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.tabIndex = -1;
  }

  private _view: EditorView | null = null;
  private _container: HTMLDivElement | null = null;
  private _lastEmittedJson = '';

  override firstUpdated(): void {
    this._container = this.querySelector('.dc-rich-text-container');
    if (!this._container) return;
    this._createView();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('mode') && this._view) {
      // Schema/plugins are baked into the view — recreate on mode change.
      this._view.destroy();
      this._view = null;
      this._createView();
      return;
    }
    if (changed.has('value') && this._view) {
      this._syncFromExternal();
    }
    if (changed.has('readOnly') && this._view) {
      this._view.setProps({ editable: () => !this.readOnly });
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._view?.destroy();
    this._view = null;
  }

  private get _schema(): Schema {
    return this.mode === 'inline' ? richTextInlineSchema : richTextBlockSchema;
  }

  private get _emptyDoc(): JsonObject {
    return this.mode === 'inline'
      ? (EMPTY_RICH_TEXT_INLINE_DOC as unknown as JsonObject)
      : ({
          ...(EMPTY_RICH_TEXT_BLOCK_DOC as unknown as JsonObject),
          content: [{ type: 'paragraph' }],
        } as JsonObject);
  }

  private _createView(): void {
    if (!this._container) return;
    const doc = this._docFromValue(this.value);
    const state = EditorState.create({
      doc,
      plugins: this._buildPlugins(),
    });
    this._lastEmittedJson = JSON.stringify(doc.toJSON());
    this._view = new EditorView(this._container, {
      state,
      editable: () => !this.readOnly,
      dispatchTransaction: (tr) => {
        if (!this._view) return;
        const newState = this._view.state.apply(tr);
        this._view.updateState(newState);
        if (tr.docChanged) {
          const json = newState.doc.toJSON();
          const serialized = JSON.stringify(json);
          if (serialized !== this._lastEmittedJson) {
            this._lastEmittedJson = serialized;
            this.dispatchEvent(
              new CustomEvent('rich-text-change', {
                detail: { value: json as JsonValue },
                bubbles: true,
                composed: true,
              }),
            );
          }
        }
      },
    });
  }

  private _syncFromExternal(): void {
    if (!this._view) return;
    const doc = this._docFromValue(this.value);
    const next = JSON.stringify(doc.toJSON());
    if (next === this._lastEmittedJson) return;
    this._lastEmittedJson = next;
    const newState = EditorState.create({
      doc,
      plugins: this._view.state.plugins,
    });
    this._view.updateState(newState);
  }

  private _docFromValue(value: JsonValue | null): ProsemirrorNode {
    const raw =
      value && typeof value === 'object' && !Array.isArray(value) ? value : this._emptyDoc;
    try {
      return ProsemirrorNode.fromJSON(this._schema, raw as Record<string, unknown>);
    } catch {
      return ProsemirrorNode.fromJSON(this._schema, this._emptyDoc as Record<string, unknown>);
    }
  }

  private _buildPlugins(): Plugin[] {
    return buildRichTextInputPlugins(this.mode, this._schema, () => !this.readOnly);
  }

  override render() {
    const placeholderClass = this.placeholder ? 'has-placeholder' : '';
    return html`
      <div
        class="dc-rich-text-container ${placeholderClass}"
        data-placeholder=${this.placeholder}
      ></div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-rich-text-input': EpistolaRichTextInput;
  }
}
