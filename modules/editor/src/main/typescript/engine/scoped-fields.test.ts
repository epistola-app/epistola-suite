import { describe, it, expect } from 'vitest';
import {
  resolveScopedFieldPaths,
  resolveSimplePath,
  augmentWithLoopContext,
} from './scoped-fields.js';
import { buildIndexes } from './indexes.js';
import { nodeId, slotId } from './test-helpers.js';
import type { FieldPath } from './schema-paths.js';
import type { TemplateDocument } from '../types/index.js';

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

function createDocWithLoop(): {
  doc: TemplateDocument;
  textNodeId: ReturnType<typeof nodeId>;
} {
  const rootId = nodeId('root');
  const rootSlotId = slotId('root-slot');
  const loopId = nodeId('loop');
  const loopSlotId = slotId('loop-body');
  const textId = nodeId('text');

  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
      [loopId]: {
        id: loopId,
        type: 'loop',
        slots: [loopSlotId],
        props: {
          expression: { raw: 'items', language: 'jsonata' },
          itemAlias: 'item',
        },
      },
      [textId]: { id: textId, type: 'text', slots: [] },
    },
    slots: {
      [rootSlotId]: { id: rootSlotId, nodeId: rootId, name: 'children', children: [loopId] },
      [loopSlotId]: { id: loopSlotId, nodeId: loopId, name: 'body', children: [textId] },
    },
    themeRef: { type: 'inherit' },
  };

  return { doc, textNodeId: textId };
}

function createDocWithNestedLoops(): {
  doc: TemplateDocument;
  innerTextId: ReturnType<typeof nodeId>;
} {
  const rootId = nodeId('root');
  const rootSlotId = slotId('root-slot');
  const outerLoopId = nodeId('outer-loop');
  const outerSlotId = slotId('outer-body');
  const innerLoopId = nodeId('inner-loop');
  const innerSlotId = slotId('inner-body');
  const textId = nodeId('text');

  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
      [outerLoopId]: {
        id: outerLoopId,
        type: 'loop',
        slots: [outerSlotId],
        props: {
          expression: { raw: 'orders', language: 'jsonata' },
          itemAlias: 'order',
        },
      },
      [innerLoopId]: {
        id: innerLoopId,
        type: 'datatable',
        slots: [innerSlotId],
        props: {
          expression: { raw: 'order.lines', language: 'jsonata' },
          itemAlias: 'line',
          indexAlias: 'idx',
        },
      },
      [textId]: { id: textId, type: 'text', slots: [] },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [outerLoopId],
      },
      [outerSlotId]: {
        id: outerSlotId,
        nodeId: outerLoopId,
        name: 'body',
        children: [innerLoopId],
      },
      [innerSlotId]: {
        id: innerSlotId,
        nodeId: innerLoopId,
        name: 'body',
        children: [textId],
      },
    },
    themeRef: { type: 'inherit' },
  };

  return { doc, innerTextId: textId };
}

// ---------------------------------------------------------------------------
// resolveScopedFieldPaths
// ---------------------------------------------------------------------------

describe('resolveScopedFieldPaths', () => {
  it('returns empty for node with no loop ancestors', () => {
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const textId = nodeId('text');

    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [textId]: { id: textId, type: 'text', slots: [] },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [textId],
        },
      },
      themeRef: { type: 'inherit' },
    };

    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(textId, doc, indexes, schemaFieldPaths);
    expect(result).toEqual([]);
  });

  it('resolves scoped fields for a single loop', () => {
    const { doc, textNodeId } = createDocWithLoop();
    const indexes = buildIndexes(doc);

    const result = resolveScopedFieldPaths(textNodeId, doc, indexes, schemaFieldPaths);
    expect(result).toHaveLength(1);

    const ctx = result[0];
    expect(ctx.itemAlias).toBe('item');
    expect(ctx.arrayExpression).toBe('items');

    const paths = ctx.fieldPaths.map((fp) => fp.path);
    expect(paths).toContain('item.name');
    expect(paths).toContain('item.price');
    expect(paths).toContain('item.date');
    expect(paths).toContain('item_index');
    expect(paths).toContain('item_first');
    expect(paths).toContain('item_last');
  });

  it('preserves field types when mapping', () => {
    const { doc, textNodeId } = createDocWithLoop();
    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(textNodeId, doc, indexes, schemaFieldPaths);

    const dateField = result[0].fieldPaths.find((fp) => fp.path === 'item.date');
    expect(dateField?.type).toBe('date');

    const indexField = result[0].fieldPaths.find((fp) => fp.path === 'item_index');
    expect(indexField?.type).toBe('integer');
  });

  it('sets scope on all scoped fields', () => {
    const { doc, textNodeId } = createDocWithLoop();
    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(textNodeId, doc, indexes, schemaFieldPaths);

    for (const fp of result[0].fieldPaths) {
      expect(fp.scope).toBe('item');
    }
  });

  it('resolves nested loops (outer first)', () => {
    const { doc, innerTextId } = createDocWithNestedLoops();
    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(innerTextId, doc, indexes, schemaFieldPaths);

    expect(result).toHaveLength(2);
    expect(result[0].itemAlias).toBe('order');
    expect(result[1].itemAlias).toBe('line');
  });

  it('includes indexAlias when configured', () => {
    const { doc, innerTextId } = createDocWithNestedLoops();
    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(innerTextId, doc, indexes, schemaFieldPaths);

    const innerCtx = result[1];
    expect(innerCtx.indexAlias).toBe('idx');
    const paths = innerCtx.fieldPaths.map((fp) => fp.path);
    expect(paths).toContain('idx');
  });

  it('returns only metadata for complex expressions', () => {
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const loopId = nodeId('loop');
    const loopSlotId = slotId('loop-body');
    const textId = nodeId('text');

    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [loopId]: {
          id: loopId,
          type: 'loop',
          slots: [loopSlotId],
          props: {
            expression: { raw: 'items[price > 100]', language: 'jsonata' },
            itemAlias: 'expensive',
          },
        },
        [textId]: { id: textId, type: 'text', slots: [] },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [loopId],
        },
        [loopSlotId]: {
          id: loopSlotId,
          nodeId: loopId,
          name: 'body',
          children: [textId],
        },
      },
      themeRef: { type: 'inherit' },
    };

    const indexes = buildIndexes(doc);
    const result = resolveScopedFieldPaths(textId, doc, indexes, schemaFieldPaths);

    expect(result).toHaveLength(1);
    const paths = result[0].fieldPaths.map((fp) => fp.path);
    // Only metadata — no item sub-properties since expression doesn't match a simple array path
    expect(paths).toEqual(['expensive_index', 'expensive_first', 'expensive_last']);
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

// ---------------------------------------------------------------------------
// augmentWithLoopContext
// ---------------------------------------------------------------------------

describe('augmentWithLoopContext', () => {
  it('injects first array item and metadata', () => {
    const data = {
      items: [
        { name: 'Widget', price: 10 },
        { name: 'Gadget', price: 20 },
      ],
    };
    const contexts = [
      {
        sourceNodeId: 'loop' as any,
        itemAlias: 'item',
        arrayExpression: 'items',
        fieldPaths: [],
      },
    ];

    const result = augmentWithLoopContext(data, contexts);
    expect(result.item).toEqual({ name: 'Widget', price: 10 });
    expect(result.item_index).toBe(0);
    expect(result.item_first).toBe(true);
    expect(result.item_last).toBe(false);
  });

  it('handles empty array gracefully', () => {
    const data = { items: [] };
    const contexts = [
      {
        sourceNodeId: 'loop' as any,
        itemAlias: 'item',
        arrayExpression: 'items',
        fieldPaths: [],
      },
    ];

    const result = augmentWithLoopContext(data, contexts);
    expect(result.item).toBeUndefined();
    expect(result.item_index).toBe(0);
    expect(result.item_first).toBe(true);
    expect(result.item_last).toBe(true);
  });

  it('handles missing array path gracefully', () => {
    const data = { other: 'value' };
    const contexts = [
      {
        sourceNodeId: 'loop' as any,
        itemAlias: 'item',
        arrayExpression: 'nonexistent',
        fieldPaths: [],
      },
    ];

    const result = augmentWithLoopContext(data, contexts);
    expect(result.item).toBeUndefined();
    expect(result.item_index).toBe(0);
  });

  it('injects indexAlias when configured', () => {
    const data = { items: [{ name: 'A' }] };
    const contexts = [
      {
        sourceNodeId: 'loop' as any,
        itemAlias: 'item',
        indexAlias: 'idx',
        arrayExpression: 'items',
        fieldPaths: [],
      },
    ];

    const result = augmentWithLoopContext(data, contexts);
    expect(result.idx).toBe(0);
  });

  it('handles nested loops with outer item reference', () => {
    const data = {
      orders: [
        {
          id: 'ORD-1',
          lines: [
            { product: 'Widget', qty: 2 },
            { product: 'Gadget', qty: 1 },
          ],
        },
      ],
    };
    const contexts = [
      {
        sourceNodeId: 'outer' as any,
        itemAlias: 'order',
        arrayExpression: 'orders',
        fieldPaths: [],
      },
      {
        sourceNodeId: 'inner' as any,
        itemAlias: 'line',
        arrayExpression: 'order.lines',
        fieldPaths: [],
      },
    ];

    const result = augmentWithLoopContext(data, contexts);
    // Outer: order = first order
    expect(result.order).toEqual(data.orders[0]);
    // Inner: line = first line of first order (resolves order.lines via augmented data)
    expect(result.line).toEqual({ product: 'Widget', qty: 2 });
  });

  it('does not mutate original data', () => {
    const data = { items: [{ name: 'A' }] };
    const original = { ...data };
    augmentWithLoopContext(data, [
      {
        sourceNodeId: 'loop' as any,
        itemAlias: 'item',
        arrayExpression: 'items',
        fieldPaths: [],
      },
    ]);
    expect(data).toEqual(original);
  });
});
