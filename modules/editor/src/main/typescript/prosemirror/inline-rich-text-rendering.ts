// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * DOM rendering for inline rich-text fragments.
 *
 * Used by the inline expression chip's preview area: when an expression
 * resolves to a ProseMirror rich-text doc, we render its inline content
 * (text + marks from the first paragraph) as DOM nodes so the chip shows
 * formatted text instead of a JSON dump.
 *
 * Block-level content (lists, additional paragraphs) is dropped — an inline
 * chip lives inside a paragraph and can't host blocks. The caller uses
 * `hasBlockContent` to surface a "wrong slot" warning to the user.
 *
 * The mark vocabulary mirrors `richTextMarks.ts` and the v1 wire schema.
 */

interface RichTextDocLike {
  type?: string;
  content?: unknown;
}

export interface InlineRichTextResult {
  fragment: DocumentFragment;
  /** True if the doc contained block-level content (lists, multiple paragraphs) that was dropped. */
  hasBlockContent: boolean;
}

/** Type guard for a value that *looks* like a ProseMirror rich-text doc. */
export function isRichTextDoc(
  value: unknown,
): value is { type: 'doc'; content: ReadonlyArray<unknown> } {
  if (!value || typeof value !== 'object') return false;
  const v = value as RichTextDocLike;
  return v.type === 'doc' && Array.isArray(v.content);
}

/**
 * Render the inline content of a rich-text doc as a DocumentFragment.
 * Only the first paragraph's text + marks are rendered; lists and additional
 * paragraphs are ignored and reported via `hasBlockContent`.
 */
export function renderInlineRichText(doc: {
  content: ReadonlyArray<unknown>;
}): InlineRichTextResult {
  const fragment = document.createDocumentFragment();
  let hasBlockContent = false;

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

/**
 * Build a DOM node for a single text run with its marks applied. Marks
 * compose by wrapping outwards: `[strong, em]` produces `<em><strong>…`.
 */
export function buildMarkedText(
  text: string,
  marks: ReadonlyArray<{ type?: string; attrs?: Record<string, unknown> }>,
): Node {
  let result: Node = document.createTextNode(text);
  for (const mark of marks) {
    result = wrapWithMark(mark, result);
  }
  return result;
}

/**
 * Wrap `inner` with the DOM element for the given mark. Unknown marks pass
 * `inner` through unchanged (forward-compat: a future mark added by the
 * editor schema renders as plain text here until this map is updated).
 */
export function wrapWithMark(
  mark: { type?: string; attrs?: Record<string, unknown> },
  inner: Node,
): Node {
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
