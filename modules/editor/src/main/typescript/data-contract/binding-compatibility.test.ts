import { describe, expect, it } from 'vitest';
import {
  formatBindingPreviewPlaceholder,
  formatFieldPathTypeLabel,
  inlineExpressionPathDisabled,
  richTextVariablePathDisabled,
} from './binding-compatibility.js';
import { RICH_TEXT_BLOCK_SCHEMA_REF, RICH_TEXT_INLINE_SCHEMA_REF } from './types.js';
import type { FieldPath } from '../engine/schema-paths.js';

const inline: FieldPath = { path: 'greeting', type: 'unknown', ref: RICH_TEXT_INLINE_SCHEMA_REF };
const block: FieldPath = { path: 'bio', type: 'unknown', ref: RICH_TEXT_BLOCK_SCHEMA_REF };
const stringFp: FieldPath = { path: 'name', type: 'string' };
const arrayFp: FieldPath = { path: 'items', type: 'array' };

describe('formatFieldPathTypeLabel', () => {
  it('uses the registered label for a known ref', () => {
    expect(formatFieldPathTypeLabel(inline)).toBe('Rich text (inline)');
    expect(formatFieldPathTypeLabel(block)).toBe('Rich text (block)');
  });

  it('falls back to FieldPath.type for non-ref paths', () => {
    expect(formatFieldPathTypeLabel(stringFp)).toBe('string');
  });
});

describe('inlineExpressionPathDisabled', () => {
  it('rejects richTextBlock fields with a Rich-Text-Variable hint', () => {
    expect(inlineExpressionPathDisabled(block)).toMatch(/Rich Text Variable component/);
  });

  it('allows richTextInline + scalars', () => {
    expect(inlineExpressionPathDisabled(inline)).toBeNull();
    expect(inlineExpressionPathDisabled(stringFp)).toBeNull();
  });

  it('rejects array and object containers', () => {
    expect(inlineExpressionPathDisabled(arrayFp)).toMatch(/array fields/);
  });
});

describe('richTextVariablePathDisabled', () => {
  it('accepts both rich-text variants', () => {
    expect(richTextVariablePathDisabled(inline)).toBeNull();
    expect(richTextVariablePathDisabled(block)).toBeNull();
  });

  it('rejects scalars and containers', () => {
    expect(richTextVariablePathDisabled(stringFp)).toMatch(/Rich text field/);
    expect(richTextVariablePathDisabled(arrayFp)).toMatch(/Rich text field/);
  });
});

describe('formatBindingPreviewPlaceholder', () => {
  it('returns "[rich text value]" for a single-paragraph doc', () => {
    expect(
      formatBindingPreviewPlaceholder({
        type: 'doc',
        content: [{ type: 'paragraph' }],
      }),
    ).toBe('[rich text value]');
  });

  it('returns "[rich text value]" for a multi-paragraph doc', () => {
    expect(
      formatBindingPreviewPlaceholder({
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: 'a' }] },
          { type: 'bullet_list', content: [] },
        ],
      }),
    ).toBe('[rich text value]');
  });

  it('returns null for non-rich-text values so the caller can format normally', () => {
    expect(formatBindingPreviewPlaceholder('plain string')).toBeNull();
    expect(formatBindingPreviewPlaceholder(42)).toBeNull();
    expect(formatBindingPreviewPlaceholder(null)).toBeNull();
    expect(formatBindingPreviewPlaceholder({ name: 'John' })).toBeNull();
    expect(formatBindingPreviewPlaceholder([1, 2, 3])).toBeNull();
  });
});
