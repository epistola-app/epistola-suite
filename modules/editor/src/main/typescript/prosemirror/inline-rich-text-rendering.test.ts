// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import {
  buildMarkedText,
  isRichTextDoc,
  renderInlineRichText,
  wrapWithMark,
} from './inline-rich-text-rendering.js';

describe('isRichTextDoc', () => {
  it('accepts an object with type doc and array content', () => {
    expect(isRichTextDoc({ type: 'doc', content: [] })).toBe(true);
  });

  it('rejects values that are not docs', () => {
    expect(isRichTextDoc(null)).toBe(false);
    expect(isRichTextDoc(undefined)).toBe(false);
    expect(isRichTextDoc('string')).toBe(false);
    expect(isRichTextDoc({ type: 'paragraph', content: [] })).toBe(false);
    expect(isRichTextDoc({ type: 'doc', content: 'not an array' })).toBe(false);
    expect(isRichTextDoc([])).toBe(false);
  });
});

describe('wrapWithMark', () => {
  function inner() {
    return document.createTextNode('x');
  }

  it.each([
    ['strong', 'STRONG'],
    ['bold', 'STRONG'],
    ['em', 'EM'],
    ['italic', 'EM'],
    ['underline', 'U'],
    ['strike', 'S'],
    ['strikethrough', 'S'],
    ['subscript', 'SUB'],
    ['superscript', 'SUP'],
  ])('wraps %s in a <%s>', (markType, expectedTag) => {
    const out = wrapWithMark({ type: markType }, inner()) as HTMLElement;
    expect(out.tagName).toBe(expectedTag);
    expect(out.firstChild?.textContent).toBe('x');
  });

  it('wraps link with href, target=_blank and rel=noopener noreferrer', () => {
    const out = wrapWithMark(
      { type: 'link', attrs: { href: 'https://example.com' } },
      inner(),
    ) as HTMLAnchorElement;
    expect(out.tagName).toBe('A');
    expect(out.getAttribute('href')).toBe('https://example.com');
    expect(out.target).toBe('_blank');
    expect(out.rel).toBe('noopener noreferrer');
  });

  it('link without href still produces <a> but without href set', () => {
    const out = wrapWithMark({ type: 'link', attrs: {} }, inner()) as HTMLAnchorElement;
    expect(out.tagName).toBe('A');
    expect(out.hasAttribute('href')).toBe(false);
  });

  it('wraps textStyle with color in a span style', () => {
    const out = wrapWithMark(
      { type: 'textStyle', attrs: { color: '#ff0000' } },
      inner(),
    ) as HTMLSpanElement;
    expect(out.tagName).toBe('SPAN');
    // happy-dom keeps the assigned color string verbatim; jsdom would normalise.
    expect(out.style.color.toLowerCase()).toMatch(/^(?:#ff0000|rgb\(255,\s*0,\s*0\))$/);
  });

  it('textStyle without color is a no-op (returns inner unchanged)', () => {
    const node = inner();
    const out = wrapWithMark({ type: 'textStyle' }, node);
    expect(out).toBe(node);
  });

  it('unknown marks are a no-op (forward-compat)', () => {
    const node = inner();
    const out = wrapWithMark({ type: 'highlight' }, node);
    expect(out).toBe(node);
  });
});

describe('buildMarkedText', () => {
  it('returns a plain text node when no marks', () => {
    const out = buildMarkedText('hello', []);
    expect(out.nodeType).toBe(3); // TEXT_NODE
    expect(out.textContent).toBe('hello');
  });

  it('layers marks outside-in (later marks wrap earlier ones)', () => {
    // marks [strong, em] → <em><strong>x</strong></em>
    const out = buildMarkedText('x', [{ type: 'strong' }, { type: 'em' }]) as HTMLElement;
    expect(out.tagName).toBe('EM');
    expect((out.firstChild as HTMLElement).tagName).toBe('STRONG');
    expect(out.textContent).toBe('x');
  });
});

describe('renderInlineRichText', () => {
  it('renders text from a single-paragraph doc', () => {
    const { fragment, hasBlockContent } = renderInlineRichText({
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: 'hello world' }],
        },
      ],
    });
    expect(hasBlockContent).toBe(false);
    expect(fragment.textContent).toBe('hello world');
  });

  it('renders text with marks producing nested DOM', () => {
    const host = document.createElement('div');
    host.appendChild(
      renderInlineRichText({
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: 'plain ' },
              { type: 'text', text: 'bold', marks: [{ type: 'strong' }] },
            ],
          },
        ],
      }).fragment,
    );
    expect(host.querySelector('strong')?.textContent).toBe('bold');
    expect(host.textContent).toBe('plain bold');
  });

  it('flags hasBlockContent when an extra paragraph is present', () => {
    const { hasBlockContent } = renderInlineRichText({
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'first' }] },
        { type: 'paragraph', content: [{ type: 'text', text: 'second' }] },
      ],
    });
    expect(hasBlockContent).toBe(true);
  });

  it('flags hasBlockContent when a non-paragraph block is present', () => {
    const { hasBlockContent } = renderInlineRichText({
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'p' }] },
        { type: 'bullet_list', content: [] },
      ],
    });
    expect(hasBlockContent).toBe(true);
  });

  it('renders only the first paragraph when block content is dropped', () => {
    const { fragment } = renderInlineRichText({
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'kept' }] },
        { type: 'paragraph', content: [{ type: 'text', text: 'dropped' }] },
      ],
    });
    expect(fragment.textContent).toBe('kept');
  });

  it('produces an empty fragment when there is no paragraph at all', () => {
    const { fragment, hasBlockContent } = renderInlineRichText({
      content: [{ type: 'bullet_list', content: [] }],
    });
    expect(hasBlockContent).toBe(true);
    expect(fragment.textContent).toBe('');
  });

  it('skips non-text inline nodes (e.g. hard_break ignored in chip preview)', () => {
    const { fragment } = renderInlineRichText({
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'a' },
            { type: 'hard_break' },
            { type: 'text', text: 'b' },
          ],
        },
      ],
    });
    expect(fragment.textContent).toBe('ab');
  });
});
