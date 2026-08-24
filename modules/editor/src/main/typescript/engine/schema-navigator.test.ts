// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { JsonSchemaNode } from '../json-schema/schema-node.js';
import {
  describeSchemaCursor,
  itemCursor,
  propertyCursor,
  resolveSchemaExpression,
  schemaRootCursor,
} from './schema-navigator.js';

const schema: JsonSchemaNode = {
  type: 'object',
  properties: {
    orders: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          lines: {
            type: 'array',
            items: {
              type: 'object',
              properties: { product: { type: 'string' } },
            },
          },
        },
      },
    },
  },
};

describe('schema navigator', () => {
  it('navigates properties and array items from one canonical root', () => {
    const root = schemaRootCursor(schema);
    const orders = propertyCursor(root, 'orders');
    const order = orders ? itemCursor(orders) : null;
    const lines = order ? propertyCursor(order, 'lines') : null;
    const line = lines ? itemCursor(lines) : null;
    const product = line ? propertyCursor(line, 'product') : null;

    expect(orders && describeSchemaCursor(orders)).toEqual({ type: 'array' });
    expect(order && describeSchemaCursor(order)).toEqual({ type: 'object' });
    expect(product && describeSchemaCursor(product)).toEqual({ type: 'string' });
    expect(propertyCursor(root, 'missing')).toBeNull();
  });

  it('resolves inner paths relative to an outer alias', () => {
    const root = schemaRootCursor(schema);
    const orders = resolveSchemaExpression('orders', root, {});
    const order = orders ? itemCursor(orders) : null;

    expect(order).not.toBeNull();
    const lines = resolveSchemaExpression('order.lines', root, { order: order! });
    expect(lines && describeSchemaCursor(lines)).toEqual({ type: 'array' });
  });

  it('rejects complex expressions that cannot be mapped to a schema cursor', () => {
    const root = schemaRootCursor(schema);

    expect(resolveSchemaExpression('orders[lines]', root, {})).toBeNull();
    expect(resolveSchemaExpression('$map(orders)', root, {})).toBeNull();
  });

  it('exposes an immutable opaque cursor instead of its traversal state', () => {
    const cursor = schemaRootCursor(schema);

    expect(Object.isFrozen(cursor)).toBe(true);
    // @ts-expect-error Schema cursor internals are intentionally unavailable to consumers.
    expect(cursor.segments).toBeUndefined();
    // @ts-expect-error The canonical schema source is intentionally navigator-owned.
    expect(cursor.source).toBeUndefined();
  });
});
