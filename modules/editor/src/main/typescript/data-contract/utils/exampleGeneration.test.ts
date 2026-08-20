// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  RICH_TEXT_INLINE_SCHEMA_REF,
  type JsonObject,
  type JsonSchema,
  type JsonValue,
} from '../types.js';
import { validateDataAgainstSchema } from './schemaValidation.js';
import { completeExampleFromSchema } from './exampleGeneration.js';
import { createSemanticExampleValues } from './semanticExampleValues.js';

function complete(schema: JsonSchema, existing: JsonObject) {
  return completeExampleFromSchema(schema, existing, createSemanticExampleValues('test-example'));
}

describe('completeExampleFromSchema', () => {
  it('keeps generic schema completion independent from semantic providers', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' }, active: { type: 'boolean' } },
    };

    expect(completeExampleFromSchema(schema, {})).toEqual({
      name: 'Example value',
      active: false,
    });
  });

  it('generates deterministic values that respect schema hints and constraints', () => {
    const schema: JsonSchema = {
      type: 'object',
      required: ['name', 'status', 'createdOn', 'amount', 'active'],
      properties: {
        name: { type: 'string', minLength: 15 },
        status: { type: 'string', enum: ['draft', 'final'] },
        country: { type: 'string', default: 'NL' },
        createdOn: { type: 'string', format: 'date' },
        updatedAt: { type: 'string', format: 'date-time' },
        email: { type: 'string', format: 'email' },
        website: { type: 'string', format: 'uri' },
        amount: { type: 'number', minimum: 2.5 },
        count: { type: 'integer', exclusiveMinimum: 3 },
        active: { type: 'boolean' },
      },
    };

    const generated = complete(schema, {});

    expect(generated.name).not.toBe('Example valuexx');
    expect((generated.name as string).length).toBeGreaterThanOrEqual(15);
    expect(generated).toMatchObject({
      status: 'draft',
      country: 'NL',
      createdOn: '2024-01-01',
      updatedAt: '2024-01-01T12:00:00Z',
      amount: 125.5,
      count: 4,
      active: true,
    });
    expect(generated.email).toMatch(/@example\.(com|net|org)$/);
    expect(() => new URL(generated.website as string)).not.toThrow();
    expect(complete(schema, {})).toEqual(generated);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('preserves authored values while completing nested objects and arrays', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        address: {
          type: 'object',
          properties: {
            street: { type: 'string' },
            city: { type: 'string' },
          },
          required: ['street', 'city'],
        },
        contacts: {
          type: 'array',
          minItems: 2,
          items: {
            type: 'object',
            properties: {
              label: { type: 'string' },
              preferred: { type: 'boolean' },
            },
            required: ['label', 'preferred'],
          },
        },
      },
    };

    const generated = complete(schema, {
      name: 'Authored name',
      address: { city: 'Amsterdam' },
      contacts: [{ label: 'Personal' }],
      custom: 'keep me',
    });

    expect(generated).toMatchObject({
      name: 'Authored name',
      address: { street: expect.any(String), city: 'Amsterdam' },
      custom: 'keep me',
    });
    const contacts = generated.contacts as JsonValue[];
    expect(contacts.length).toBeGreaterThanOrEqual(2);
    expect(contacts.length).toBeLessThanOrEqual(3);
    expect(contacts[0]).toEqual({ label: 'Personal', preferred: false });
    for (const [index, contact] of contacts.slice(1).entries()) {
      expect(contact).toEqual({ label: `Example label ${index + 2}`, preferred: false });
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('uses defaults as a base and completes their missing nested values', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        settings: {
          type: 'object',
          default: { locale: 'nl-NL' },
          properties: {
            locale: { type: 'string' },
            notifications: { type: 'boolean' },
          },
        },
      },
    };

    expect(complete(schema, {})).toEqual({
      settings: { locale: 'nl-NL', notifications: false },
    });
  });

  it('supports local schema references and registered rich-text references', () => {
    const schema: JsonSchema = {
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: { city: { type: 'string' } },
          required: ['city'],
        },
      },
      properties: {
        address: { $ref: '#/$defs/address' },
        introduction: { $ref: RICH_TEXT_INLINE_SCHEMA_REF },
      },
    };

    const generated = complete(schema, {});

    expect(generated.address).toEqual({ city: expect.any(String) });
    expect(generated.introduction).toMatchObject({ type: 'doc' });
  });

  it('completes referenced compositions and unions in advanced nested schemas', () => {
    const schema: JsonSchema = {
      type: 'object',
      $defs: {
        contactDetails: {
          type: 'object',
          properties: {
            email: { type: 'string', format: 'email' },
            phoneNumber: { type: 'string' },
          },
          required: ['email'],
        },
        person: {
          type: 'object',
          allOf: [{ $ref: '#/$defs/contactDetails' }],
          properties: {
            firstName: { type: 'string' },
            lastName: { type: 'string' },
            address: {
              type: 'object',
              properties: { city: { type: 'string' } },
              required: ['city'],
            },
          },
          required: ['firstName', 'lastName', 'address'],
        },
      },
      properties: {
        applicant: { $ref: '#/$defs/person' },
        subject: {
          oneOf: [
            { $ref: '#/$defs/person' },
            {
              type: 'object',
              properties: {
                organizationName: { type: 'string' },
                registrationNumber: { type: 'string' },
              },
              required: ['organizationName', 'registrationNumber'],
            },
          ],
        },
        alternateSubjects: {
          type: 'array',
          minItems: 2,
          items: {
            oneOf: [
              { $ref: '#/$defs/person' },
              {
                type: 'object',
                properties: {
                  organizationName: { type: 'string' },
                  registrationNumber: { type: 'string' },
                },
                required: ['organizationName', 'registrationNumber'],
              },
            ],
          },
        },
        correspondenceAddress: {
          anyOf: [
            { type: 'null' },
            {
              type: 'object',
              properties: { city: { type: 'string' } },
            },
          ],
        },
      },
    };

    const generated = complete(schema, {
      applicant: {
        firstName: 'Authored name',
        lastName: '',
        email: '',
        phoneNumber: null,
        address: { city: '' },
      },
      subject: { organizationName: 'Authored organization', registrationNumber: '' },
      correspondenceAddress: null,
    });

    expect(generated.applicant).toMatchObject({
      firstName: 'Authored name',
      lastName: expect.any(String),
      email: expect.stringMatching(/@example\./),
      phoneNumber: expect.any(String),
      address: { city: expect.any(String) },
    });
    expect(generated.subject).toMatchObject({
      organizationName: 'Authored organization',
      registrationNumber: expect.any(String),
    });
    expect((generated.alternateSubjects as JsonObject[]).length).toBeGreaterThanOrEqual(2);
    expect((generated.alternateSubjects as JsonObject[]).length).toBeLessThanOrEqual(3);
    for (const item of generated.alternateSubjects as JsonObject[]) {
      expect(item).toMatchObject({
        firstName: expect.any(String),
        email: expect.stringMatching(/@example\./),
        phoneNumber: expect.any(String),
        address: { city: expect.any(String) },
      });
    }
    expect(generated.correspondenceAddress).toBeNull();
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('repairs primitive values left by earlier autofill for object unions', () => {
    const schema: JsonSchema = {
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: { city: { type: 'string' } },
          required: ['city'],
        },
        person: {
          type: 'object',
          properties: {
            name: { type: 'string' },
            address: { $ref: '#/$defs/address' },
          },
          required: ['name', 'address'],
        },
      },
      properties: {
        subject: { oneOf: [{ $ref: '#/$defs/person' }] },
        alternateSubjects: {
          type: 'array',
          minItems: 2,
          items: { oneOf: [{ $ref: '#/$defs/person' }] },
        },
      },
    };

    const generated = complete(schema, {
      subject: 'Example subject 1',
      alternateSubjects: ['Example alternateSubjects 1'],
    });

    expect(generated.subject).toMatchObject({
      name: expect.any(String),
      address: { city: expect.any(String) },
    });
    expect((generated.alternateSubjects as JsonObject[]).length).toBeGreaterThanOrEqual(2);
    expect((generated.alternateSubjects as JsonObject[]).length).toBeLessThanOrEqual(3);
    for (const subject of generated.alternateSubjects as JsonObject[]) {
      expect(subject).toMatchObject({
        name: expect.any(String),
        address: { city: expect.any(String) },
      });
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('honors array minimum and maximum constraints', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        values: { type: 'array', items: { type: 'string' }, maxItems: 0 },
        single: { type: 'array', items: { type: 'string' }, maxItems: 1 },
        requiredItems: { type: 'array', items: { type: 'string' }, minItems: 5 },
      },
    };

    const generated = complete(schema, {});

    expect(generated.values).toEqual([]);
    expect(generated.single).toHaveLength(1);
    expect(generated.requiredItems).toHaveLength(5);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('generates distinct formatted values for primitive array items', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        recipients: {
          type: 'array',
          minItems: 3,
          items: { type: 'string', format: 'email' },
        },
      },
    };

    const generated = complete(schema, {});
    const recipients = generated.recipients as string[];

    expect(recipients).toHaveLength(3);
    expect(new Set(recipients)).toHaveProperty('size', 3);
    for (const email of recipients) expect(email).toMatch(/@example\./);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('generates a distinct coherent person for every object array item', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        people: {
          type: 'array',
          minItems: 3,
          items: {
            type: 'object',
            properties: {
              firstName: { type: 'string' },
              lastName: { type: 'string' },
              name: { type: 'string' },
              email: { type: 'string', format: 'email' },
            },
            required: ['firstName', 'lastName', 'name', 'email'],
          },
        },
      },
    };

    const generated = complete(schema, {});
    const people = generated.people as JsonObject[];

    expect(people).toHaveLength(3);
    expect(new Set(people.map((person) => person.name))).toHaveProperty('size', 3);
    expect(new Set(people.map((person) => person.email))).toHaveProperty('size', 3);
    for (const person of people) {
      if (typeof person.firstName !== 'string' || typeof person.lastName !== 'string') {
        throw new TypeError('Generated person names must be strings');
      }
      expect(person.name).toBe(`${person.firstName} ${person.lastName}`);
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('keeps explicit schema formats and numeric constraints authoritative', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        phoneNumber: { type: 'string', format: 'email' },
        website: { type: 'string', format: 'date' },
        amount: { type: 'number', maximum: 100 },
        age: { type: 'integer', minimum: 50 },
      },
    };

    const generated = complete(schema, {});

    expect(generated.phoneNumber).toMatch(/@example\.(com|net|org)$/);
    expect(generated.website).toBe('2024-01-01');
    expect(generated.amount).toBe(100);
    expect(generated.age).toBe(50);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('generates through recursively nested arrays and objects', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        arr: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              arr: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: { name: { type: 'string' } },
                  required: ['name'],
                },
              },
            },
            required: ['arr'],
          },
        },
      },
      required: ['arr'],
    };

    const generated = complete(schema, {});

    const outerItems = generated.arr as JsonValue[];
    expect(outerItems.length).toBeGreaterThanOrEqual(2);
    expect(outerItems.length).toBeLessThanOrEqual(3);
    for (const outerItem of outerItems) {
      const innerItems = (outerItem as JsonObject).arr as JsonValue[];
      expect(innerItems.length).toBeGreaterThanOrEqual(2);
      expect(innerItems.length).toBeLessThanOrEqual(3);
      for (const innerItem of innerItems) {
        expect(innerItem).toEqual({ name: expect.any(String) });
      }
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });
});
