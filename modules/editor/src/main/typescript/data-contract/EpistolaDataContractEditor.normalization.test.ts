// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { EpistolaDataContractEditor } from './EpistolaDataContractEditor.js';
import type { JsonSchema } from './types.js';

afterEach(() => document.body.replaceChildren());

describe('schema normalization', () => {
  it('preserves stored advanced schemas while keeping their examples editable', async () => {
    const schema: JsonSchema = {
      type: 'object',
      $defs: {
        contact: {
          type: 'object',
          properties: { name: { type: 'string' } },
          required: ['name'],
          allOf: [
            {
              type: 'object',
              properties: { email: { type: 'string', format: 'email' } },
              required: ['email'],
            },
          ],
        },
      },
      properties: {
        contacts: {
          type: 'array',
          items: { $ref: '#/$defs/contact' },
        },
      },
    };
    const editor = new EpistolaDataContractEditor();
    editor.init(
      schema,
      [
        {
          id: 'example-1',
          name: 'Example 1',
          data: { contacts: [{ name: 'Ada', email: 'ada@example.com' }] },
        },
      ],
      {},
    );
    document.body.append(editor);
    await editor.updateComplete;

    expect(editor.contractState?.schemaEditMode).toBe('json-only');
    expect(editor.contractState?.rawJsonSchema).toEqual(schema);
    expect(editor.contractState?.schema?.properties?.contacts.items).toEqual({
      $ref: '#/$defs/contact',
    });
    expect(editor.textContent).not.toContain('Schema normalized for visual editing');
    expect(editor.querySelector<HTMLInputElement>('#dc-field-contacts-0-email')?.value).toBe(
      'ada@example.com',
    );
  });

  it('normalizes a safe UI import into the visual editor', async () => {
    const originalSchema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const editor = new EpistolaDataContractEditor();
    editor.init(
      originalSchema,
      [{ id: 'example-1', name: 'Example 1', data: { name: 'Ada' } }],
      {},
    );
    document.body.append(editor);
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('button[title="Import a JSON Schema"]')!.click();
    await editor.updateComplete;

    const textarea = editor.querySelector<HTMLTextAreaElement>('#dc-import-textarea')!;
    textarea.value = JSON.stringify({
      type: 'object',
      $defs: {
        contact: {
          type: 'object',
          properties: { email: { type: 'string', format: 'email' } },
          required: ['email'],
        },
      },
      properties: {
        contact: { $ref: '#/$defs/contact' },
      },
    });
    editor.querySelector<HTMLButtonElement>('.dc-import-dialog .ep-btn-primary')!.click();
    await editor.updateComplete;

    expect(editor.contractState?.schemaEditMode).toBe('visual');
    expect(editor.contractState?.schema).not.toHaveProperty('$defs');
    expect(editor.contractState?.schema?.properties?.contact).toMatchObject({
      type: 'object',
      properties: { email: { type: 'string', format: 'email' } },
      required: ['email'],
    });
    expect(editor.textContent).toContain('Schema normalized for visual editing');
    expect(editor.contractState?.isSchemaDirty).toBe(true);
  });

  it('imports schemas that cannot be normalized unchanged in JSON-only mode', async () => {
    const originalSchema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const editor = new EpistolaDataContractEditor();
    editor.init(
      originalSchema,
      [{ id: 'example-1', name: 'Example 1', data: { name: 'Ada' } }],
      {},
    );
    document.body.append(editor);
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('button[title="Import a JSON Schema"]')!.click();
    await editor.updateComplete;

    const textarea = editor.querySelector<HTMLTextAreaElement>('#dc-import-textarea')!;
    textarea.value = JSON.stringify({
      type: 'object',
      properties: {
        subject: {
          oneOf: [{ type: 'string' }, { type: 'integer' }],
        },
      },
    });
    editor.querySelector<HTMLButtonElement>('.dc-import-dialog .ep-btn-primary')!.click();
    await editor.updateComplete;

    expect(editor.querySelector('.dc-import-dialog')).toBeNull();
    expect(editor.contractState?.schemaEditMode).toBe('json-only');
    expect(editor.contractState?.rawJsonSchema).toEqual({
      type: 'object',
      properties: {
        subject: {
          oneOf: [{ type: 'string' }, { type: 'integer' }],
        },
      },
    });
    expect(editor.textContent).toContain('Visual editor is disabled');
    expect(editor.contractState?.isSchemaDirty).toBe(true);
  });

  it('rejects an arbitrary JSON object instead of storing it as the schema', async () => {
    const originalSchema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const editor = new EpistolaDataContractEditor();
    editor.init(
      originalSchema,
      [{ id: 'example-1', name: 'Example 1', data: { name: 'Ada' } }],
      {},
    );
    document.body.append(editor);
    await editor.updateComplete;

    editor.querySelector<HTMLButtonElement>('button[title="Import a JSON Schema"]')!.click();
    await editor.updateComplete;

    const textarea = editor.querySelector<HTMLTextAreaElement>('#dc-import-textarea')!;
    textarea.value = JSON.stringify({
      schemaVersion: 5,
      resource: {
        type: 'template',
        slug: 'advanced-data-contract',
        dataModel: {
          type: 'object',
          properties: { caseReference: { type: 'string' } },
        },
      },
    });
    editor.querySelector<HTMLButtonElement>('.dc-import-dialog .ep-btn-primary')!.click();
    await editor.updateComplete;

    expect(editor.querySelector('.dc-import-error')?.textContent).toContain(
      'must require an object at its root',
    );
    expect(editor.contractState?.schema).toEqual(originalSchema);
    expect(editor.contractState?.isSchemaDirty).toBe(false);
  });
});
