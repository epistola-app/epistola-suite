// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { createSemanticExampleValues } from './semanticExampleValues.js';

describe('createSemanticExampleValues', () => {
  it('creates a coherent fictional person from Dutch and English field names', () => {
    const values = createSemanticExampleValues();
    const firstName = values.string({ name: 'voornaam' });
    const lastName = values.string({ name: 'lastName' });

    expect(firstName).toBeTruthy();
    expect(lastName).toBeTruthy();
    expect(values.string({ name: 'fullName' })).toBe(`${firstName} ${lastName}`);
    expect(values.string({ name: 'emailAddress' })).toMatch(/@example\.(com|net|org)$/);
  });

  it('uses titles and descriptions when a technical property name has no semantic meaning', () => {
    const values = createSemanticExampleValues();

    expect(values.string({ name: 'field_1', title: 'Postcode' })).toBeTruthy();
    expect(
      values.string({
        name: 'field_2',
        description: 'Het telefoonnummer waarop de aanvrager bereikbaar is.',
      }),
    ).toMatch(/^\+31/);
    expect(values.number({ name: 'field_3', description: 'Leeftijd van de aanvrager' })).toBe(42);
  });

  it('recognizes semantic numbers and booleans without claiming unknown fields', () => {
    const values = createSemanticExampleValues();

    expect(values.number({ name: 'amount' })).toBe(125.5);
    expect(values.number({ name: 'aantalItems' })).toBe(3);
    expect(values.boolean({ name: 'isActive' })).toBe(true);
    expect(values.string({ name: 'opaqueValue' })).toBeUndefined();
    expect(values.number({ name: 'opaqueValue' })).toBeUndefined();
    expect(values.boolean({ name: 'opaqueValue' })).toBeUndefined();
  });

  it('reproduces the same generated profile for the same seed', () => {
    const first = createSemanticExampleValues(1234);
    const second = createSemanticExampleValues(1234);

    const fields = ['voornaam', 'achternaam', 'woonplaats', 'straat', 'postcode', 'zaaknummer'];
    expect(fields.map((name) => first.string({ name }))).toEqual(
      fields.map((name) => second.string({ name })),
    );
  });

  it('uses an example-specific string seed to vary fictional profiles', () => {
    const first = createSemanticExampleValues('example-a');
    const second = createSemanticExampleValues('example-b');

    expect(first.string({ name: 'fullName' })).not.toBe(second.string({ name: 'fullName' }));
  });
});
