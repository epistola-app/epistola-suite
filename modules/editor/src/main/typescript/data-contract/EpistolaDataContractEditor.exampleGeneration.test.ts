// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';
import type { JsonSchema } from './types.js';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';

const schema: JsonSchema = {
  type: 'object',
  required: ['name', 'active', 'address'],
  properties: {
    name: { type: 'string' },
    active: { type: 'boolean' },
    address: {
      type: 'object',
      required: ['city'],
      properties: {
        city: { type: 'string' },
      },
    },
  },
};

async function mountEditor(readOnly = false): Promise<EpistolaDataContractEditor> {
  const editor = new EpistolaDataContractEditor();
  editor.init(
    schema,
    [{ id: 'example-1', name: 'Example 1', data: { name: 'Authored' } }],
    {},
    readOnly,
  );
  document.body.append(editor);
  await editor.updateComplete;
  return editor;
}

afterEach(() => document.body.replaceChildren());

describe('example generation', () => {
  it('fills missing values, preserves authored data, and can be undone in one step', async () => {
    const editor = await mountEditor();
    const generate = editor.querySelector<HTMLButtonElement>('.dc-example-generate-btn')!;

    generate.click();
    generate.click();
    await vi.waitFor(() => expect(editor.contractState!.dataExamples[0].data.active).toBe(true));
    await editor.updateComplete;

    expect(editor.contractState!.dataExamples[0].data).toMatchObject({
      name: 'Authored',
      active: true,
      address: { city: expect.any(String) },
    });
    expect(editor.querySelector('.dc-validation-success')?.textContent).toContain('Valid');

    const undo = editor.querySelector<HTMLButtonElement>(
      '.dc-example-toolbar button[aria-label="Undo"]',
    )!;
    expect(undo.disabled).toBe(false);
    undo.click();
    await editor.updateComplete;

    expect(editor.contractState!.dataExamples[0].data).toEqual({ name: 'Authored' });
  });

  it('disables generation in read-only mode', async () => {
    const editor = await mountEditor(true);

    expect(editor.querySelector<HTMLButtonElement>('.dc-example-generate-btn')!.disabled).toBe(
      true,
    );
  });

  it('renders generated addresses and alternate subjects for a partial advanced union', async () => {
    const advancedSchema: JsonSchema = {
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: {
            street: { type: 'string' },
            postalCode: { type: 'string' },
            city: { type: 'string' },
          },
          required: ['street', 'postalCode', 'city'],
        },
        organization: {
          type: 'object',
          properties: {
            organizationName: { type: 'string' },
            registrationNumber: { type: 'string' },
            address: { $ref: '#/$defs/address' },
          },
          required: ['organizationName', 'registrationNumber', 'address'],
        },
        person: {
          type: 'object',
          properties: {
            firstName: { type: 'string' },
            address: { $ref: '#/$defs/address' },
          },
          required: ['firstName', 'address'],
        },
      },
      properties: {
        subject: {
          oneOf: [{ $ref: '#/$defs/person' }, { $ref: '#/$defs/organization' }],
        },
        alternateSubjects: {
          type: 'array',
          minItems: 2,
          items: {
            oneOf: [{ $ref: '#/$defs/person' }, { $ref: '#/$defs/organization' }],
          },
        },
      },
      required: ['subject', 'alternateSubjects'],
    };
    const editor = new EpistolaDataContractEditor();
    editor.init(
      advancedSchema,
      [
        {
          id: 'partial-example',
          name: 'Partial example',
          data: {
            subject: 'Example subject 1',
            alternateSubjects: ['Example alternateSubjects 1'],
          },
        },
      ],
      {},
    );
    document.body.append(editor);
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('.dc-example-generate-btn')!.click();
    await vi.waitFor(() => {
      const generated = editor.contractState!.dataExamples[0].data;
      expect(generated.subject).toMatchObject({ address: { city: expect.any(String) } });
      expect((generated.alternateSubjects as unknown[]).length).toBeGreaterThanOrEqual(2);
    });
    await editor.updateComplete;

    expect(
      editor.querySelector<HTMLInputElement>('#dc-field-subject-address-city')?.value,
    ).not.toBe('');
    expect(
      editor.querySelector<HTMLInputElement>('#dc-field-alternateSubjects-0-address-city')?.value,
    ).not.toBe('');
    expect(editor.querySelector('.dc-validation-success')?.textContent).toContain('Valid');
  });
});
