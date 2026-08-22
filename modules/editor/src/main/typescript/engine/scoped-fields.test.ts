// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { JsonSchemaNode } from '../json-schema/schema-node.js';
import type { Node } from '../types/index.js';
import { schemaRootCursor } from './schema-navigator.js';
import { materializeScopeDeclaration, type SchemaScopeEnvironment } from './schema-scopes.js';
import { buildIterationScope, resolveSimplePath } from './scoped-fields.js';
import { nodeId } from './test-helpers.js';

const schema: JsonSchemaNode = {
  type: 'object',
  properties: {
    items: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          price: { type: 'number' },
          date: { type: 'string', format: 'date' },
        },
      },
    },
    orders: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          lines: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                product: { type: 'string' },
                qty: { type: 'integer' },
              },
            },
          },
        },
      },
    },
    deliveryRoutes: {
      type: 'array',
      items: {
        type: 'array',
        items: {
          type: 'object',
          properties: { destination: { type: 'string' } },
        },
      },
    },
  },
};

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

function rootEnvironment(): SchemaScopeEnvironment {
  return { dataRoot: schemaRootCursor(schema), bindings: {} };
}

function materialize(node: Node, environment = rootEnvironment()) {
  const declaration = buildIterationScope(node, {});
  return declaration ? materializeScopeDeclaration(declaration, environment) : null;
}

describe('buildIterationScope', () => {
  it('returns null without an expression or props', () => {
    expect(buildIterationScope(makeLoopNode('item', ''), {})).toBeNull();
    expect(buildIterationScope({ id: nodeId('loop'), type: 'loop', slots: [] }, {})).toBeNull();
  });

  it('declares its schema binding instead of receiving projected fields', () => {
    const declaration = buildIterationScope(makeLoopNode('row', 'items', 'idx'), {})!;

    expect(declaration.schemaBindings).toEqual([
      {
        alias: 'row',
        source: { kind: 'array-item-expression', expression: 'items' },
        scopeKind: 'iteration',
        description: 'Current iteration item',
        includeAlias: true,
      },
    ]);
    expect(declaration.variables.map((field) => field.path)).toEqual([
      'row_index',
      'row_first',
      'row_last',
      'idx',
    ]);
  });

  it('materializes an item alias and its typed children', () => {
    const scope = materialize(makeLoopNode('item', 'items'))!;

    expect(scope.variables).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'item', type: 'object' }),
        expect.objectContaining({ path: 'item.name', type: 'string' }),
        expect.objectContaining({ path: 'item.price', type: 'number' }),
        expect.objectContaining({ path: 'item.date', type: 'date' }),
        expect.objectContaining({ path: 'item_index', type: 'integer' }),
      ]),
    );
    expect(scope.variables.every((field) => field.scope === 'item')).toBe(true);
    expect(scope.variables.every((field) => field.scopeKind === 'iteration')).toBe(true);
  });

  it('resolves a nested loop from the outer alias cursor', () => {
    const outer = materialize(makeLoopNode('order', 'orders'))!;
    const inner = materialize(makeLoopNode('line', 'order.lines'), outer.environment)!;

    expect(inner.variables).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'line', type: 'object' }),
        expect.objectContaining({ path: 'line.product', type: 'string' }),
        expect.objectContaining({ path: 'line.qty', type: 'integer' }),
      ]),
    );
  });

  it('resolves array items nested directly inside arrays', () => {
    const outer = materialize(makeLoopNode('route', 'deliveryRoutes'))!;
    const inner = materialize(makeLoopNode('stop', 'route'), outer.environment)!;

    expect(outer.variables).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'route', type: 'array' }),
        expect.objectContaining({ path: 'route[].destination', type: 'string' }),
      ]),
    );
    expect(inner.variables).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'stop', type: 'object' }),
        expect.objectContaining({ path: 'stop.destination', type: 'string' }),
      ]),
    );
  });

  it('keeps complex JSONata expressions valid but schema-unknown', () => {
    const scope = materialize(makeLoopNode('expensive', 'items[price > 100]'))!;

    expect(scope.variables.map((field) => field.path)).toEqual([
      'expensive',
      'expensive_index',
      'expensive_first',
      'expensive_last',
    ]);
    expect(scope.variables[0].type).toBe('unknown');
  });

  it('returns preview data with the first array item and metadata', () => {
    const scope = buildIterationScope(makeLoopNode('item', 'items', 'idx'), {
      evaluationContext: {
        items: [
          { name: 'Widget', price: 10 },
          { name: 'Gadget', price: 20 },
        ],
      },
    })!;

    expect(scope.evaluationData).toEqual({
      item: { name: 'Widget', price: 10 },
      item_index: 0,
      item_first: true,
      item_last: false,
      idx: 0,
    });
  });

  it('handles empty, missing, and nested arrays in preview data', () => {
    const empty = buildIterationScope(makeLoopNode('item', 'items'), {
      evaluationContext: { items: [] },
    })!;
    const missing = buildIterationScope(makeLoopNode('item', 'missing'), {
      evaluationContext: { other: true },
    })!;
    const nested = buildIterationScope(makeLoopNode('line', 'order.lines'), {
      evaluationContext: { order: { lines: [{ product: 'Widget' }] } },
    })!;

    expect(empty.evaluationData).toMatchObject({
      item_index: 0,
      item_first: true,
      item_last: true,
    });
    expect(empty.evaluationData?.item).toBeUndefined();
    expect(missing.evaluationData?.item).toBeUndefined();
    expect(nested.evaluationData?.line).toEqual({ product: 'Widget' });
  });
});

describe('resolveSimplePath', () => {
  it('resolves paths and safely rejects missing or non-object segments', () => {
    expect(resolveSimplePath({ name: 'John' }, 'name')).toBe('John');
    expect(resolveSimplePath({ a: { b: { c: 42 } } }, 'a.b.c')).toBe(42);
    expect(resolveSimplePath({ a: 1 }, 'b')).toBeUndefined();
    expect(resolveSimplePath({ a: 'string' }, 'a.b')).toBeUndefined();
    expect(resolveSimplePath({ a: null }, 'a.b')).toBeUndefined();
  });
});
