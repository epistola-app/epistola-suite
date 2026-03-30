import { describe, it, expect } from 'vitest';
import { buildIterationScope, resolveSimplePath } from './scoped-fields.js';
import type { FieldPath } from './schema-paths.js';
import type { Node } from '../types/index.js';
import { nodeId } from './test-helpers.js';

// ---------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------

const schemaFieldPaths: FieldPath[] = [
  { path: 'name', type: 'string' },
  { path: 'items', type: 'array' },
  { path: 'items[].name', type: 'string' },
  { path: 'items[].price', type: 'number' },
  { path: 'items[].date', type: 'date' },
  { path: 'orders', type: 'array' },
  { path: 'orders[].id', type: 'string' },
  { path: 'orders[].lines', type: 'array' },
  { path: 'orders[].lines[].product', type: 'string' },
  { path: 'orders[].lines[].qty', type: 'integer' },
];

function makeLoopNode(alias: string, expression: string, indexAlias?: string): Node {
  return {
    id: nodeId('loop'),
    type: 'loop',
    slots: [],
    props: {
      expression: { raw: expression, language: 'jsonata' },
      itemAlias: alias,
      indexAlias,
    },
  };
}

// ---------------------------------------------------------------------------
// buildIterationScope
// ---------------------------------------------------------------------------

describe('buildIterationScope', () => {
  it('returns null for empty expression', () => {
    const node = makeLoopNode('item', '');
    expect(buildIterationScope(node, { schemaFieldPaths })).toBeNull();
  });

  it('returns null for node without props', () => {
    const node: Node = { id: nodeId('loop'), type: 'loop', slots: [] };
    expect(buildIterationScope(node, { schemaFieldPaths })).toBeNull();
  });

  it('returns scoped variables for a simple loop', () => {
    const node = makeLoopNode('item', 'items');
    const scope = buildIterationScope(node, { schemaFieldPaths });

    expect(scope).not.toBeNull();
    const paths = scope!.variables.map((fp) => fp.path);
    expect(paths).toContain('item.name');
    expect(paths).toContain('item.price');
    expect(paths).toContain('item.date');
    expect(paths).toContain('item_index');
    expect(paths).toContain('item_first');
    expect(paths).toContain('item_last');
  });

  it('preserves field types when mapping', () => {
    const node = makeLoopNode('item', 'items');
    const scope = buildIterationScope(node, { schemaFieldPaths })!;

    const dateField = scope.variables.find((fp) => fp.path === 'item.date');
    expect(dateField?.type).toBe('date');

    const indexField = scope.variables.find((fp) => fp.path === 'item_index');
    expect(indexField?.type).toBe('integer');
  });

  it('sets scope on all variables', () => {
    const node = makeLoopNode('item', 'items');
    const scope = buildIterationScope(node, { schemaFieldPaths })!;

    for (const fp of scope.variables) {
      expect(fp.scope).toBe('item');
    }
  });

  it('uses custom alias', () => {
    const node = makeLoopNode('row', 'items');
    const scope = buildIterationScope(node, { schemaFieldPaths })!;

    const paths = scope.variables.map((fp) => fp.path);
    expect(paths).toContain('row.name');
    expect(paths).toContain('row_index');
    expect(paths).not.toContain('item.name');
  });

  it('includes indexAlias when configured', () => {
    const node = makeLoopNode('item', 'items', 'idx');
    const scope = buildIterationScope(node, { schemaFieldPaths })!;

    const paths = scope.variables.map((fp) => fp.path);
    expect(paths).toContain('idx');
  });

  it('returns only metadata for complex expressions', () => {
    const node = makeLoopNode('expensive', 'items[price > 100]');
    const scope = buildIterationScope(node, { schemaFieldPaths })!;

    const paths = scope.variables.map((fp) => fp.path);
    expect(paths).toEqual(['expensive_index', 'expensive_first', 'expensive_last']);
  });

  it('returns evaluation data with first array item', () => {
    const node = makeLoopNode('item', 'items');
    const evaluationContext = {
      items: [
        { name: 'Widget', price: 10 },
        { name: 'Gadget', price: 20 },
      ],
    };
    const scope = buildIterationScope(node, { schemaFieldPaths, evaluationContext })!;

    expect(scope.evaluationData).toEqual({
      item: { name: 'Widget', price: 10 },
      item_index: 0,
      item_first: true,
      item_last: false,
    });
  });

  it('handles empty array in evaluation data', () => {
    const node = makeLoopNode('item', 'items');
    const scope = buildIterationScope(node, {
      schemaFieldPaths,
      evaluationContext: { items: [] },
    })!;

    expect(scope.evaluationData?.item).toBeUndefined();
    expect(scope.evaluationData?.item_index).toBe(0);
    expect(scope.evaluationData?.item_first).toBe(true);
    expect(scope.evaluationData?.item_last).toBe(true);
  });

  it('handles missing array in evaluation data', () => {
    const node = makeLoopNode('item', 'nonexistent');
    const scope = buildIterationScope(node, {
      schemaFieldPaths,
      evaluationContext: { other: 'value' },
    })!;

    expect(scope.evaluationData?.item).toBeUndefined();
    expect(scope.evaluationData?.item_index).toBe(0);
  });

  it('includes indexAlias in evaluation data', () => {
    const node = makeLoopNode('item', 'items', 'idx');
    const scope = buildIterationScope(node, {
      schemaFieldPaths,
      evaluationContext: { items: [{ name: 'A' }] },
    })!;

    expect(scope.evaluationData?.idx).toBe(0);
  });

  it('resolves nested path via evaluation context', () => {
    const node = makeLoopNode('line', 'order.lines');
    const evaluationContext = {
      order: {
        lines: [
          { product: 'Widget', qty: 2 },
          { product: 'Gadget', qty: 1 },
        ],
      },
    };
    const scope = buildIterationScope(node, { schemaFieldPaths, evaluationContext })!;

    expect(scope.evaluationData?.line).toEqual({ product: 'Widget', qty: 2 });
  });
});

// ---------------------------------------------------------------------------
// resolveSimplePath
// ---------------------------------------------------------------------------

describe('resolveSimplePath', () => {
  it('resolves a top-level key', () => {
    expect(resolveSimplePath({ name: 'John' }, 'name')).toBe('John');
  });

  it('resolves a nested path', () => {
    expect(resolveSimplePath({ a: { b: { c: 42 } } }, 'a.b.c')).toBe(42);
  });

  it('returns undefined for missing path', () => {
    expect(resolveSimplePath({ a: 1 }, 'b')).toBeUndefined();
  });

  it('returns undefined for path through non-object', () => {
    expect(resolveSimplePath({ a: 'string' }, 'a.b')).toBeUndefined();
  });

  it('returns undefined for path through null', () => {
    expect(resolveSimplePath({ a: null }, 'a.b')).toBeUndefined();
  });
});
