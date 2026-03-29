import { describe, it, expect } from 'vitest';
import { rewriteAliasInExpression, rewriteExpressionsInContent } from './alias-rewrite.js';

// ---------------------------------------------------------------------------
// rewriteAliasInExpression
// ---------------------------------------------------------------------------

describe('rewriteAliasInExpression', () => {
  it('rewrites a bare alias', () => {
    expect(rewriteAliasInExpression('item', 'item', 'row')).toBe('row');
  });

  it('rewrites a dot-path prefix', () => {
    expect(rewriteAliasInExpression('item.name', 'item', 'row')).toBe('row.name');
  });

  it('rewrites nested dot-path', () => {
    expect(rewriteAliasInExpression('item.address.city', 'item', 'row')).toBe('row.address.city');
  });

  it('rewrites metadata fields', () => {
    expect(rewriteAliasInExpression('item_index', 'item', 'row')).toBe('row_index');
    expect(rewriteAliasInExpression('item_first', 'item', 'row')).toBe('row_first');
    expect(rewriteAliasInExpression('item_last', 'item', 'row')).toBe('row_last');
  });

  it('rewrites inside $formatDate', () => {
    expect(rewriteAliasInExpression("$formatDate(item.date, 'dd-MM-yyyy')", 'item', 'row')).toBe(
      "$formatDate(row.date, 'dd-MM-yyyy')",
    );
  });

  it('rewrites in string concatenation', () => {
    expect(rewriteAliasInExpression('"Name: " & item.name', 'item', 'row')).toBe(
      '"Name: " & row.name',
    );
  });

  it('does NOT match longer variable names', () => {
    expect(rewriteAliasInExpression('items', 'item', 'row')).toBe('items');
    expect(rewriteAliasInExpression('items.name', 'item', 'row')).toBe('items.name');
    expect(rewriteAliasInExpression('itemCount', 'item', 'row')).toBe('itemCount');
  });

  it('returns unchanged for same alias', () => {
    expect(rewriteAliasInExpression('item.name', 'item', 'item')).toBe('item.name');
  });

  it('returns unchanged for empty old alias', () => {
    expect(rewriteAliasInExpression('item.name', '', 'row')).toBe('item.name');
  });

  it('handles multiple occurrences', () => {
    expect(rewriteAliasInExpression('item.name & " " & item.last', 'item', 'row')).toBe(
      'row.name & " " & row.last',
    );
  });
});

// ---------------------------------------------------------------------------
// rewriteExpressionsInContent
// ---------------------------------------------------------------------------

describe('rewriteExpressionsInContent', () => {
  it('rewrites expression nodes in PM JSON', () => {
    const content = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'Hello ' },
            { type: 'expression', attrs: { expression: 'item.name', isNew: false } },
          ],
        },
      ],
    };

    const result = rewriteExpressionsInContent(content, 'item', 'row') as Record<string, unknown>;
    const para = (result.content as unknown[])[0] as Record<string, unknown>;
    const expr = (para.content as unknown[])[1] as Record<string, unknown>;
    expect((expr.attrs as Record<string, unknown>).expression).toBe('row.name');
  });

  it('returns same reference when nothing changes', () => {
    const content = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: 'No expressions here' }],
        },
      ],
    };

    const result = rewriteExpressionsInContent(content, 'item', 'row');
    expect(result).toBe(content);
  });

  it('rewrites multiple expressions', () => {
    const content = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'expression', attrs: { expression: 'item.name' } },
            { type: 'text', text: ' - ' },
            { type: 'expression', attrs: { expression: "$formatDate(item.date, 'dd-MM-yyyy')" } },
          ],
        },
      ],
    };

    const result = rewriteExpressionsInContent(content, 'item', 'row') as Record<string, unknown>;
    const para = (result.content as unknown[])[0] as Record<string, unknown>;
    const children = para.content as Record<string, unknown>[];
    expect((children[0].attrs as Record<string, unknown>).expression).toBe('row.name');
    expect((children[2].attrs as Record<string, unknown>).expression).toBe(
      "$formatDate(row.date, 'dd-MM-yyyy')",
    );
  });

  it('returns content unchanged for null/undefined', () => {
    expect(rewriteExpressionsInContent(null, 'item', 'row')).toBeNull();
    expect(rewriteExpressionsInContent(undefined, 'item', 'row')).toBeUndefined();
  });

  it('preserves text nodes unchanged', () => {
    const content = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'item.name is a field' },
            { type: 'expression', attrs: { expression: 'item.price' } },
          ],
        },
      ],
    };

    const result = rewriteExpressionsInContent(content, 'item', 'row') as Record<string, unknown>;
    const para = (result.content as unknown[])[0] as Record<string, unknown>;
    const children = para.content as Record<string, unknown>[];
    // Text node NOT rewritten
    expect(children[0].text).toBe('item.name is a field');
    // Expression node IS rewritten
    expect((children[1].attrs as Record<string, unknown>).expression).toBe('row.price');
  });
});
