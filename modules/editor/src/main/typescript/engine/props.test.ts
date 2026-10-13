// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { afterEach, describe, expect, it } from 'vitest';
import { setNestedValue } from './props.js';

describe('setNestedValue', () => {
  afterEach(() => {
    delete (Object.prototype as Record<string, unknown>).polluted;
  });

  it('writes a nested value, creating missing parents', () => {
    const props: Record<string, unknown> = { condition: { raw: 'a' } };
    setNestedValue(props, 'condition.raw', 'b');
    setNestedValue(props, 'style.margin.top', '4pt');
    expect(props).toEqual({ condition: { raw: 'b' }, style: { margin: { top: '4pt' } } });
  });

  it.each(['__proto__.polluted', 'constructor.prototype.polluted', '__proto__'])(
    'never writes through the prototype chain for %s',
    (path) => {
      const props: Record<string, unknown> = {};
      setNestedValue(props, path, 'yes');
      expect(({} as Record<string, unknown>).polluted).toBeUndefined();
      expect(Object.getPrototypeOf(props)).toBe(Object.prototype);
    },
  );

  it('treats an inherited name as a plain own key', () => {
    const props: Record<string, unknown> = {};
    setNestedValue(props, 'constructor.prototype.polluted', 'yes');
    expect(Object.hasOwn(props, 'constructor')).toBe(true);
    expect(props.constructor).toEqual({ prototype: { polluted: 'yes' } });
  });
});
