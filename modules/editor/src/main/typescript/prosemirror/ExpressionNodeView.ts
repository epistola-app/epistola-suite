/**
 * ProseMirror NodeView for expression chips.
 *
 * Renders as an inline `<span class="expression-chip">` showing either:
 * - The resolved value from the current data example (e.g., "John Doe")
 * - The raw expression as fallback (e.g., `{{customer.name}}`)
 *
 * Click opens the shared expression dialog.
 *
 * - Enter → save expression
 * - Escape → cancel (delete if isNew, else close)
 * - Auto-opens dialog when `isNew` is true
 */

import type { Node as ProsemirrorNode } from 'prosemirror-model';
import type { EditorView, NodeView } from 'prosemirror-view';
import type { FieldPath } from '../engine/schema-paths.js';
import { evaluateExpression, formatResolvedValue } from '../engine/resolve-expression.js';
import { inlineExpressionPathDisabled } from '../data-contract/binding-compatibility.js';
import { openExpressionDialog } from '../ui/expression-dialog.js';

export interface ExpressionNodeViewOptions {
  getFieldPaths: () => FieldPath[];
  getExampleData?: () => Record<string, unknown> | undefined;
}

export class ExpressionNodeView implements NodeView {
  /** All live instances, for bulk refresh when data example changes. */
  private static _instances = new Set<ExpressionNodeView>();

  /** Refresh the display of all live expression chips (e.g., after example switch). */
  static refreshAll(): void {
    for (const instance of ExpressionNodeView._instances) {
      instance._updateDisplay();
    }
  }

  dom: HTMLSpanElement;
  private _leftBrace: HTMLSpanElement;
  private _content: HTMLSpanElement;
  private _rightBrace: HTMLSpanElement;
  private _node: ProsemirrorNode;
  private _view: EditorView;
  private _getPos: () => number | undefined;
  private _dialogOpen = false;
  private _getFieldPaths: () => FieldPath[];
  private _getExampleData: (() => Record<string, unknown> | undefined) | undefined;

  /** Monotonic counter to discard stale async resolution results. */
  private _displayGeneration = 0;

  constructor(
    node: ProsemirrorNode,
    view: EditorView,
    getPos: () => number | undefined,
    options: ExpressionNodeViewOptions,
  ) {
    this._node = node;
    this._view = view;
    this._getPos = getPos;
    this._getFieldPaths = options.getFieldPaths;
    this._getExampleData = options.getExampleData;

    // Create the chip element
    this.dom = document.createElement('span');
    this.dom.className = 'expression-chip';
    this.dom.contentEditable = 'false';

    this._leftBrace = document.createElement('span');
    this._leftBrace.className = 'expression-chip-brace expression-chip-brace-left';
    this._leftBrace.textContent = '{{';

    this._content = document.createElement('span');
    this._content.className = 'expression-chip-content';

    this._rightBrace = document.createElement('span');
    this._rightBrace.className = 'expression-chip-brace expression-chip-brace-right';
    this._rightBrace.textContent = '}}';

    this.dom.append(this._leftBrace, this._content, this._rightBrace);
    this._updateDisplay();

    // Click → open dialog
    this.dom.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this._openDialog();
    });

    // Auto-open for new expressions
    if (node.attrs.isNew) {
      // Small delay so the node is rendered and positioned in the DOM
      requestAnimationFrame(() => this._openDialog());
    }

    ExpressionNodeView._instances.add(this);
  }

  update(node: ProsemirrorNode): boolean {
    if (node.type !== this._node.type) return false;
    this._node = node;
    this._updateDisplay();
    return true;
  }

  destroy(): void {
    ExpressionNodeView._instances.delete(this);
    this._displayGeneration++; // invalidate any in-flight async resolution
  }

  // Prevent ProseMirror from handling events inside the chip
  stopEvent(): boolean {
    return true;
  }

  ignoreMutation(): boolean {
    return true;
  }

  // ---------------------------------------------------------------------------
  // Display
  // ---------------------------------------------------------------------------

  private _updateDisplay(): void {
    const expr = this._node.attrs.expression as string;
    if (!expr) {
      this._setRawExpressionDisplay('...');
      this.dom.title = 'Click to edit expression';
      return;
    }

    const data = this._getExampleData?.();
    if (!data) {
      // No data example available — show raw expression
      this._setRawExpressionDisplay(expr);
      this.dom.title = expr;
      return;
    }

    // Show raw expression immediately, then kick off async resolution
    this._setRawExpressionDisplay(expr);
    this.dom.title = expr;
    this._resolveAndDisplay(expr, data);
  }

  private _resolveAndDisplay(expr: string, data: Record<string, unknown>): void {
    const generation = ++this._displayGeneration;

    evaluateExpression(expr, data).then((result) => {
      // Discard stale result (example switched or node destroyed since we started)
      if (generation !== this._displayGeneration) return;

      // Rich-text doc: render inline content with marks; warn if block content
      // (lists, multi-paragraph) was passed — inline bindings can't host blocks.
      if (isRichTextDoc(result)) {
        const { fragment, hasBlockContent } = renderInlineRichText(result);
        this._setResolvedDomDisplay(fragment, hasBlockContent);
        this.dom.title = hasBlockContent
          ? `{{${expr}}} — block content (lists, paragraphs) was dropped; use a Rich Text Variable component for block content.`
          : `{{${expr}}}`;
        return;
      }

      const formatted = formatResolvedValue(result);
      if (formatted !== undefined) {
        // Resolved: show value as text, expression in tooltip
        this._setResolvedDisplay(formatted);
        this.dom.title = `{{${expr}}}`;
      }
      // Unresolved: keep the {{expression}} already set in _updateDisplay
    });
  }

  private _setRawExpressionDisplay(expr: string): void {
    this.dom.classList.add('is-raw');
    this._leftBrace.hidden = false;
    this._rightBrace.hidden = false;
    this._content.textContent = expr;
  }

  private _setResolvedDisplay(value: string): void {
    this.dom.classList.remove('is-raw');
    this.dom.classList.remove('expression-chip-has-block-warn');
    this._leftBrace.hidden = true;
    this._rightBrace.hidden = true;
    this._content.textContent = value;
  }

  private _setResolvedDomDisplay(fragment: DocumentFragment, hasBlockContent: boolean): void {
    this.dom.classList.remove('is-raw');
    this.dom.classList.toggle('expression-chip-has-block-warn', hasBlockContent);
    this._leftBrace.hidden = true;
    this._rightBrace.hidden = true;
    this._content.replaceChildren(fragment);
    if (hasBlockContent) {
      const warn = document.createElement('span');
      warn.className = 'expression-chip-warn-icon';
      warn.textContent = '⚠️';
      warn.setAttribute('aria-hidden', 'true');
      this._content.appendChild(warn);
    }
  }

  // ---------------------------------------------------------------------------
  // Dialog
  // ---------------------------------------------------------------------------

  private _openDialog(): void {
    if (this._dialogOpen) return;
    this._dialogOpen = true;

    const expr = this._node.attrs.expression as string;

    openExpressionDialog({
      initialValue: expr,
      fieldPaths: this._getFieldPaths(),
      getExampleData: this._getExampleData,
      enableBuilderMode: true,
      label: 'Expression',
      placeholder: 'e.g. customer.name',
      // Inline expression chips live inside paragraphs — block-level rich
      // text fields can't render here, so the picker greys them out.
      pathDisabled: inlineExpressionPathDisabled,
    }).then(({ value }) => {
      this._dialogOpen = false;
      if (value !== null) {
        this._updateAttrs({ expression: value, isNew: false });
      } else if (this._node.attrs.isNew) {
        this._deleteNode();
      }
      this._view.focus();
    });
  }

  // ---------------------------------------------------------------------------
  // ProseMirror transactions
  // ---------------------------------------------------------------------------

  private _updateAttrs(attrs: Record<string, unknown>): void {
    const pos = this._getPos();
    if (pos == null) return;
    const tr = this._view.state.tr.setNodeMarkup(pos, undefined, {
      ...this._node.attrs,
      ...attrs,
    });
    this._view.dispatch(tr);
  }

  private _deleteNode(): void {
    const pos = this._getPos();
    if (pos == null) return;
    const tr = this._view.state.tr.delete(pos, pos + this._node.nodeSize);
    this._view.dispatch(tr);
  }
}

// ---------------------------------------------------------------------------
// Rich-text doc detection + inline DOM rendering for chip preview
// ---------------------------------------------------------------------------

interface RichTextDocLike {
  type?: string;
  content?: unknown;
}

function isRichTextDoc(value: unknown): value is { type: 'doc'; content: ReadonlyArray<unknown> } {
  if (!value || typeof value !== 'object') return false;
  const v = value as RichTextDocLike;
  return v.type === 'doc' && Array.isArray(v.content);
}

interface InlineRichTextResult {
  fragment: DocumentFragment;
  /** True if the doc contained block-level content (lists, multiple paragraphs) that was dropped. */
  hasBlockContent: boolean;
}

/**
 * Render the inline content of a rich-text doc as a DocumentFragment (text + marks).
 * Block-level content (lists, multi-paragraphs) is dropped — chip context can
 * only host inline content. Caller surfaces a warning when this happens.
 */
function renderInlineRichText(doc: { content: ReadonlyArray<unknown> }): InlineRichTextResult {
  const fragment = document.createDocumentFragment();
  let hasBlockContent = false;

  // Collect inline content from the first paragraph; flag any other blocks.
  const blocks = doc.content;
  let firstParagraph: { content?: unknown } | null = null;
  for (const block of blocks) {
    if (!block || typeof block !== 'object') continue;
    const b = block as { type?: string; content?: unknown };
    if (b.type === 'paragraph') {
      if (firstParagraph === null) {
        firstParagraph = b;
      } else {
        hasBlockContent = true;
      }
    } else {
      hasBlockContent = true;
    }
  }

  const inline = Array.isArray(firstParagraph?.content) ? firstParagraph.content : [];
  for (const node of inline) {
    if (!node || typeof node !== 'object') continue;
    const n = node as {
      type?: string;
      text?: string;
      marks?: ReadonlyArray<{ type?: string; attrs?: Record<string, unknown> }>;
    };
    if (n.type !== 'text') continue;
    fragment.appendChild(buildMarkedText(n.text ?? '', n.marks ?? []));
  }

  return { fragment, hasBlockContent };
}

function buildMarkedText(
  text: string,
  marks: ReadonlyArray<{ type?: string; attrs?: Record<string, unknown> }>,
): Node {
  let result: Node = document.createTextNode(text);
  for (const mark of marks) {
    result = wrapWithMark(mark, result);
  }
  return result;
}

function wrapWithMark(mark: { type?: string; attrs?: Record<string, unknown> }, inner: Node): Node {
  let el: HTMLElement | null = null;
  switch (mark.type) {
    case 'strong':
    case 'bold':
      el = document.createElement('strong');
      break;
    case 'em':
    case 'italic':
      el = document.createElement('em');
      break;
    case 'underline':
      el = document.createElement('u');
      break;
    case 'strike':
    case 'strikethrough':
      el = document.createElement('s');
      break;
    case 'subscript':
      el = document.createElement('sub');
      break;
    case 'superscript':
      el = document.createElement('sup');
      break;
    case 'link': {
      const a = document.createElement('a');
      const href = mark.attrs?.href as string | undefined;
      if (href) a.href = href;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      el = a;
      break;
    }
    case 'textStyle': {
      const color = mark.attrs?.color as string | undefined;
      if (color) {
        el = document.createElement('span');
        el.style.color = color;
      }
      break;
    }
    default:
      return inner;
  }
  if (!el) return inner;
  el.appendChild(inner);
  return el;
}
