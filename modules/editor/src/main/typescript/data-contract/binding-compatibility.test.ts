import { describe, expect, it } from 'vitest';
import {
  formatBindingPreview,
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
const dateFp: FieldPath = { path: 'born', type: 'date' };
const datetimeFp: FieldPath = { path: 'seenAt', type: 'datetime' };
const unknownFp: FieldPath = { path: 'mystery', type: 'unknown' };

describe('formatFieldPathTypeLabel', () => {
  it('uses the registered label for a known ref', () => {
    expect(formatFieldPathTypeLabel(inline)).toBe('Rich text (inline)');
    expect(formatFieldPathTypeLabel(block)).toBe('Rich text (block)');
  });

  it('uses the shared registry label for scalar field types', () => {
    expect(formatFieldPathTypeLabel(stringFp)).toBe('Text');
    expect(formatFieldPathTypeLabel(dateFp)).toBe('Date');
    expect(formatFieldPathTypeLabel(datetimeFp)).toBe('Date-time');
  });

  it('falls back to the raw type for an unregistered type', () => {
    expect(formatFieldPathTypeLabel(unknownFp)).toBe('unknown');
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

describe('formatBindingPreview', () => {
  it('extracts text from a single-paragraph doc', () => {
    expect(
      formatBindingPreview({
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Hello' }] }],
      }),
    ).toBe('Hello');
  });

  it('extracts text from a multi-paragraph doc', () => {
    expect(
      formatBindingPreview({
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: 'First' }] },
          { type: 'paragraph', content: [{ type: 'text', text: 'Second' }] },
        ],
      }),
    ).toBe('First Second');
  });

  it('returns "(empty)" for a doc with no text', () => {
    expect(
      formatBindingPreview({
        type: 'doc',
        content: [{ type: 'paragraph' }],
      }),
    ).toBe('(empty)');
  });

  it('returns "(empty)" for a doc with non-array content', () => {
    expect(
      formatBindingPreview({
        type: 'doc',
        content: 'not-an-array',
      }),
    ).toBe('(empty)');
  });

  it('handles hard_break as a space', () => {
    expect(
      formatBindingPreview({
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: 'Line 1' },
              { type: 'hard_break' },
              { type: 'text', text: 'Line 2' },
            ],
          },
        ],
      }),
    ).toBe('Line 1 Line 2');
  });

  it('returns null for non-rich-text values so the caller can format normally', () => {
    expect(formatBindingPreview('plain string')).toBeNull();
    expect(formatBindingPreview(42)).toBeNull();
    expect(formatBindingPreview(null)).toBeNull();
    expect(formatBindingPreview({ name: 'John' })).toBeNull();
    expect(formatBindingPreview([1, 2, 3])).toBeNull();
  });
});
