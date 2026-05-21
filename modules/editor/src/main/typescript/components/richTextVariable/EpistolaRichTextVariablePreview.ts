/**
 * EpistolaRichTextVariablePreview — Canvas preview for the richTextVariable component.
 *
 * Resolves the bound rich-text doc against the current example data and
 * renders a static HTML preview using the same node/mark vocabulary the
 * backend converter understands. If the binding does not resolve to a
 * rich-text doc shape, it shows a placeholder showing the binding path.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { NodeId } from '../../types/index.js';
import { evaluateExpression } from '../../engine/resolve-expression.js';

interface RichTextDocLike {
  type?: string;
  content?: unknown;
}

@customElement('epistola-rich-text-variable-preview')
export class EpistolaRichTextVariablePreview extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property({ attribute: false }) nodeId?: NodeId;
  @property({ type: String }) binding = '';

  @state() private _resolved: unknown = null;
  private _unsubExample: (() => void) | null = null;
  private _refreshToken = 0;

  override connectedCallback(): void {
    super.connectedCallback();
    void this._refresh();
    if (this.engine) {
      this._unsubExample = this.engine.events.on('example:change', () => void this._refresh());
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._unsubExample?.();
    this._unsubExample = null;
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('binding') || changed.has('engine') || changed.has('nodeId')) {
      void this._refresh();
    }
  }

  private async _refresh(): Promise<void> {
    if (!this.engine || !this.nodeId || !this.binding.trim()) {
      this._resolved = null;
      return;
    }
    const token = ++this._refreshToken;
    const ctx = this.engine.getEvaluationContextAt(this.nodeId);
    const value = await evaluateExpression(this.binding, ctx, this.engine.locale);
    if (token !== this._refreshToken) return;
    this._resolved = value;
  }

  private _isRichTextDoc(value: unknown): value is RichTextDocLike & {
    content: ReadonlyArray<unknown>;
  } {
    if (!value || typeof value !== 'object') return false;
    const v = value as RichTextDocLike;
    return v.type === 'doc' && Array.isArray(v.content);
  }

  override render() {
    if (!this.binding) {
      return html`
        <div class="rich-text-variable-empty">
          <span>Set a binding in the inspector.</span>
        </div>
      `;
    }
    if (!this._isRichTextDoc(this._resolved)) {
      return html`
        <div class="rich-text-variable-placeholder" data-binding=${this.binding}>
          <span class="rich-text-variable-label">Rich Text:</span>
          <code>${this.binding}</code>
        </div>
      `;
    }
    return html`<div class="rich-text-variable-rendered">${this._renderDoc(this._resolved)}</div>`;
  }

  private _renderDoc(doc: { content: ReadonlyArray<unknown> }): unknown {
    return doc.content.map((block) => this._renderBlock(block));
  }

  private _renderBlock(block: unknown): unknown {
    if (!block || typeof block !== 'object') return nothing;
    const b = block as { type?: string; content?: unknown; attrs?: Record<string, unknown> };
    if (b.type === 'paragraph') {
      const inline = Array.isArray(b.content) ? b.content : [];
      return html`<p>${inline.map((node) => this._renderInline(node))}</p>`;
    }
    if (b.type === 'bullet_list' || b.type === 'bulletList') {
      const items = Array.isArray(b.content) ? b.content : [];
      return html`<ul>
        ${items.map((item) => this._renderListItem(item))}
      </ul>`;
    }
    if (b.type === 'ordered_list' || b.type === 'orderedList') {
      const items = Array.isArray(b.content) ? b.content : [];
      return html`<ol>
        ${items.map((item) => this._renderListItem(item))}
      </ol>`;
    }
    return nothing;
  }

  private _renderListItem(item: unknown): unknown {
    if (!item || typeof item !== 'object') return nothing;
    const i = item as { content?: unknown };
    const inner = Array.isArray(i.content) ? i.content : [];
    return html`<li>${inner.map((block) => this._renderBlock(block))}</li>`;
  }

  private _renderInline(node: unknown): unknown {
    if (!node || typeof node !== 'object') return nothing;
    const n = node as {
      type?: string;
      text?: string;
      marks?: ReadonlyArray<{ type?: string; attrs?: Record<string, unknown> }>;
    };
    if (n.type !== 'text') return nothing;
    let result: unknown = n.text ?? '';
    const marks = n.marks ?? [];
    for (const mark of marks) {
      result = this._wrapMark(mark, result);
    }
    return result;
  }

  private _wrapMark(
    mark: { type?: string; attrs?: Record<string, unknown> },
    inner: unknown,
  ): unknown {
    switch (mark.type) {
      case 'strong':
      case 'bold':
        return html`<strong>${inner}</strong>`;
      case 'em':
      case 'italic':
        return html`<em>${inner}</em>`;
      case 'underline':
        return html`<u>${inner}</u>`;
      case 'strike':
      case 'strikethrough':
        return html`<s>${inner}</s>`;
      case 'subscript':
        return html`<sub>${inner}</sub>`;
      case 'superscript':
        return html`<sup>${inner}</sup>`;
      case 'link': {
        const href = (mark.attrs?.href as string | undefined) ?? '#';
        return html`<a href=${href}>${inner}</a>`;
      }
      case 'textStyle': {
        const color = mark.attrs?.color as string | undefined;
        if (color) {
          return html`<span style="color: ${color}">${inner}</span>`;
        }
        return inner;
      }
      default:
        return inner;
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-rich-text-variable-preview': EpistolaRichTextVariablePreview;
  }
}
