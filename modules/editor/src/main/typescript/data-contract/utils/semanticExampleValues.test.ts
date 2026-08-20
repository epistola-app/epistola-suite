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

  it('generates distinct emails from a schema format without relying on the field name', () => {
    const values = createSemanticExampleValues('email-list');

    const first = values.string({ name: 'recipient' }, { format: 'email' });
    const second = values.string({ name: 'recipient' }, { format: 'email' });

    expect(first).toMatch(/@example\.(com|net|org)$/);
    expect(second).toMatch(/@example\.(com|net|org)$/);
    expect(second).not.toBe(first);
  });

  it('recognizes semantic numbers and booleans and labels unknown string fields clearly', () => {
    const values = createSemanticExampleValues();

    expect(values.number({ name: 'amount' })).toBe(125.5);
    expect(values.number({ name: 'aantalItems' })).toBe(3);
    expect(values.boolean({ name: 'isActive' })).toBe(true);
    expect(values.string({ name: 'opaqueValue' })).toBe('Example opaque value 1');
    expect(values.string({ name: 'opaqueValue' })).toBe('Example opaque value 2');
    expect(values.number({ name: 'opaqueValue' })).toBeUndefined();
    expect(values.boolean({ name: 'opaqueValue' })).toBeUndefined();
  });

  it('suggests representative array sizes within schema constraints', () => {
    const values = createSemanticExampleValues('array-example');
    const field = { name: 'items' };

    expect(values.arrayLength(field, { minimum: 0 })).toBeGreaterThanOrEqual(2);
    expect(values.arrayLength(field, { minimum: 0 })).toBeLessThanOrEqual(3);
    expect(values.arrayLength(field, { minimum: 0, maximum: 1 })).toBe(1);
    expect(values.arrayLength(field, { minimum: 0, maximum: 0 })).toBe(0);
    expect(values.arrayLength(field, { minimum: 5 })).toBe(5);
  });

  it('covers common fields from Dutch municipal contracts', () => {
    const values = createSemanticExampleValues('municipal-example');

    expect(values.string({ name: 'beklaagde' })).not.toContain('Example beklaagde');
    expect(values.string({ name: 'contactfunctionaris' })).not.toContain(
      'Example contactfunctionaris',
    );
    expect(values.string({ name: 'team' })).toMatch(/^Team /);
    expect(['e-mail', 'telefoon', 'brief']).toContain(values.string({ name: 'communicatie' }));
    expect(values.string({ name: 'kenmerk_intern' })).toMatch(/^Z\/\d{2}\/\d{6}$/);
    expect(values.string({ name: 'kenmerk_extern' })).toMatch(/^EXT-\d{4}$/);
    expect(values.string({ name: 'bsn' })).toBe('111222333');
    expect(values.string({ name: 'licenceplate' })).toMatch(/^[A-Z]{2}-\d{2}-[A-Z]{2}$/);
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
