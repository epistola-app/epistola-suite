// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  REF_TYPES,
  classifyValue,
  findRefType,
  getRefTypeById,
  isAnyRefType,
  isRefType,
} from './ref-types.js';
import { RICH_TEXT_BLOCK_SCHEMA_REF, RICH_TEXT_INLINE_SCHEMA_REF } from './types.js';

describe('ref-types registry', () => {
  it('registers richTextInline with its canonical URL', () => {
    const t = findRefType(RICH_TEXT_INLINE_SCHEMA_REF);
    expect(t).not.toBeNull();
    expect(t!.id).toBe('richTextInline');
    expect(t!.url).toBe(RICH_TEXT_INLINE_SCHEMA_REF);
    expect(t!.label).toBe('Rich text (inline)');
  });

  it('registers richTextBlock with its canonical URL', () => {
    const t = findRefType(RICH_TEXT_BLOCK_SCHEMA_REF);
    expect(t).not.toBeNull();
    expect(t!.id).toBe('richTextBlock');
    expect(t!.label).toBe('Rich text (block)');
  });

  it('returns null for unknown URLs and undefined input', () => {
    expect(findRefType('https://example.com/other.json')).toBeNull();
    expect(findRefType(undefined)).toBeNull();
  });

  it('getRefTypeById throws for unknown ids', () => {
    expect(() => getRefTypeById('richTextInline')).not.toThrow();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => getRefTypeById('nonexistent' as any)).toThrow();
  });

  it('isRefType discriminates correctly', () => {
    expect(isRefType(RICH_TEXT_INLINE_SCHEMA_REF, 'richTextInline')).toBe(true);
    expect(isRefType(RICH_TEXT_INLINE_SCHEMA_REF, 'richTextBlock')).toBe(false);
    expect(isRefType(undefined, 'richTextInline')).toBe(false);
  });

  it('isAnyRefType matches any of the listed ids', () => {
    expect(isAnyRefType(RICH_TEXT_BLOCK_SCHEMA_REF, ['richTextInline', 'richTextBlock'])).toBe(
      true,
    );
    expect(isAnyRefType('https://example.com/x.json', ['richTextInline', 'richTextBlock'])).toBe(
      false,
    );
  });
});

describe('REF_TYPES default values', () => {
  it('richTextInline default is a single-paragraph doc', () => {
    const t = getRefTypeById('richTextInline');
    const v = t.defaultValue() as { type: string; content: unknown[] };
    expect(v.type).toBe('doc');
    expect(v.content).toHaveLength(1);
    expect((v.content[0] as { type: string }).type).toBe('paragraph');
  });

  it('richTextBlock default is an empty doc', () => {
    const t = getRefTypeById('richTextBlock');
    const v = t.defaultValue() as { type: string; content: unknown[] };
    expect(v.type).toBe('doc');
    expect(v.content).toEqual([]);
  });
});

describe('REF_TYPES shallowShapeCheck', () => {
  const inline = getRefTypeById('richTextInline');
  const block = getRefTypeById('richTextBlock');

  describe('inline', () => {
    it('accepts a single-paragraph doc', () => {
      expect(
        inline.shallowShapeCheck({ type: 'doc', content: [{ type: 'paragraph' }] }),
      ).toBeNull();
    });

    it('rejects multi-paragraph docs', () => {
      const reason = inline.shallowShapeCheck({
        type: 'doc',
        content: [{ type: 'paragraph' }, { type: 'paragraph' }],
      });
      expect(reason).toMatch(/exactly one paragraph/);
    });

    it('rejects docs whose first child is not a paragraph', () => {
      const reason = inline.shallowShapeCheck({
        type: 'doc',
        content: [{ type: 'bullet_list' }],
      });
      expect(reason).toMatch(/single paragraph node/);
    });

    it('rejects non-objects', () => {
      expect(inline.shallowShapeCheck('plain string')).toMatch(/inline rich-text/);
      expect(inline.shallowShapeCheck(null)).toMatch(/inline rich-text/);
    });
  });

  describe('block', () => {
    it('accepts an empty doc', () => {
      expect(block.shallowShapeCheck({ type: 'doc', content: [] })).toBeNull();
    });

    it('accepts multi-paragraph docs', () => {
      expect(
        block.shallowShapeCheck({
          type: 'doc',
          content: [{ type: 'paragraph' }, { type: 'paragraph' }],
        }),
      ).toBeNull();
    });

    it('rejects non-doc top-level types', () => {
      expect(block.shallowShapeCheck({ type: 'paragraph', content: [] })).toMatch(/rich-text/);
    });
  });
});

describe('classifyValue', () => {
  it('returns richTextInline for a single-paragraph doc', () => {
    expect(classifyValue({ type: 'doc', content: [{ type: 'paragraph' }] })?.id).toBe(
      'richTextInline',
    );
  });

  it('returns richTextBlock for a doc with lists', () => {
    expect(
      classifyValue({
        type: 'doc',
        content: [{ type: 'paragraph' }, { type: 'bullet_list' }],
      })?.id,
    ).toBe('richTextBlock');
  });

  it('returns null for non-doc values', () => {
    expect(classifyValue({ name: 'plain object' })).toBeNull();
    expect(classifyValue('string')).toBeNull();
  });
});

describe('REF_TYPES is non-empty', () => {
  it('exports at least the two rich-text entries', () => {
    expect(REF_TYPES.length).toBeGreaterThanOrEqual(2);
    expect(REF_TYPES.map((t) => t.id)).toEqual(
      expect.arrayContaining(['richTextInline', 'richTextBlock']),
    );
  });
});
