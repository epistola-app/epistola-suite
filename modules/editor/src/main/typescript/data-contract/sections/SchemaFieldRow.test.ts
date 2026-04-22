import { nothing } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import { renderSchemaFieldListItem } from './SchemaFieldRow.js';
import type { SchemaField } from '../types.js';

interface TemplateLike {
  strings: readonly string[];
  values?: readonly unknown[];
}

function isTemplateLike(value: unknown): value is TemplateLike {
  return typeof value === 'object' && value !== null && 'strings' in value;
}

function templateToMarkup(template: unknown): string {
  if (!isTemplateLike(template)) {
    if (
      typeof template === 'string' ||
      typeof template === 'number' ||
      typeof template === 'boolean'
    ) {
      return String(template);
    }
    if (Array.isArray(template)) {
      return template.map((item) => templateToMarkup(item)).join('');
    }
    if (typeof template === 'function') {
      return '[function]';
    }
    return '';
  }

  const values = template.values ?? [];
  const parts: string[] = [];
  for (let i = 0; i < template.strings.length; i += 1) {
    parts.push(template.strings[i]);
    if (i < values.length) {
      parts.push(templateToMarkup(values[i]));
    }
  }
  return parts.join('');
}

function collectBoundValues(template: unknown, marker: string): unknown[] {
  if (!isTemplateLike(template)) {
    if (Array.isArray(template)) {
      return template.flatMap((item) => collectBoundValues(item, marker));
    }
    return [];
  }

  const values = template.values ?? [];
  const matches: unknown[] = [];
  for (let i = 0; i < values.length; i += 1) {
    if (template.strings[i]?.includes(marker)) {
      matches.push(values[i]);
    }
    matches.push(...collectBoundValues(values[i], marker));
  }
  return matches;
}

const stringField: SchemaField = {
  id: 'f1',
  name: 'Name',
  type: 'string',
  required: true,
};

const objectField: SchemaField = {
  id: 'f2',
  name: 'Address',
  type: 'object',
  required: false,
  nestedFields: [{ id: 'f2-1', name: 'Street', type: 'string', required: false }],
};

const arrayField: SchemaField = {
  id: 'f3',
  name: 'Items',
  type: 'array',
  required: false,
  arrayItemType: 'string',
};

const arrayObjectField: SchemaField = {
  id: 'f4',
  name: 'Users',
  type: 'array',
  required: false,
  arrayItemType: 'object',
  nestedFields: [{ id: 'f4-1', name: 'Email', type: 'string', required: false }],
};

describe('renderSchemaFieldListItem', () => {
  it('renders primitive field details and wires row selection', () => {
    const onToggle = vi.fn();
    const onSelect = vi.fn();

    const result = renderSchemaFieldListItem(stringField, 0, new Set(), 'f1', onToggle, onSelect);
    const markup = templateToMarkup(result);
    const clickHandlers = collectBoundValues(result, '@click=');
    const styleValues = collectBoundValues(result, 'style=');

    expect(markup).toContain('Name');
    expect(markup).toContain('Text');
    expect(markup).toContain('dc-field-list-item-selected');
    expect(markup).toContain('dc-field-required-dot');
    expect(markup).toContain('dc-field-expand-spacer');
    expect(markup).not.toContain('dc-field-expand-btn');
    expect(styleValues).toEqual([nothing]);
    expect(clickHandlers).toHaveLength(1);

    const event = { stopPropagation: vi.fn() } as unknown as Event;
    const selectHandler = clickHandlers[0] as (event: Event) => void;
    selectHandler(event);

    expect(event.stopPropagation).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('f1');
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('renders collapsed object fields and wires expand toggles separately from row selection', () => {
    const onToggle = vi.fn();
    const onSelect = vi.fn();

    const result = renderSchemaFieldListItem(objectField, 0, new Set(), null, onToggle, onSelect);
    const markup = templateToMarkup(result);
    const clickHandlers = collectBoundValues(result, '@click=');
    const expandedValues = collectBoundValues(result, 'aria-expanded=');
    const titleValues = collectBoundValues(result, 'title=');

    expect(markup).toContain('Address');
    expect(markup).toContain('dc-field-expand-btn');
    expect(markup).not.toContain('Street');
    expect(titleValues).toContain('Expand');
    expect(markup).toContain('Expand nested fields');
    expect(String(expandedValues[0])).toBe('false');
    expect(clickHandlers).toHaveLength(2);

    const rowEvent = { stopPropagation: vi.fn() } as unknown as Event;
    const toggleEvent = { stopPropagation: vi.fn() } as unknown as Event;
    const selectHandler = clickHandlers[0] as (event: Event) => void;
    const toggleHandler = clickHandlers[1] as (event: Event) => void;

    selectHandler(rowEvent);
    toggleHandler(toggleEvent);

    expect(rowEvent.stopPropagation).toHaveBeenCalledTimes(1);
    expect(toggleEvent.stopPropagation).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('f2');
    expect(onToggle).toHaveBeenCalledWith('f2');
  });

  it('renders expanded nested fields with nested depth styling', () => {
    const result = renderSchemaFieldListItem(
      objectField,
      0,
      new Set(['f2']),
      null,
      vi.fn(),
      vi.fn(),
    );
    const markup = templateToMarkup(result);
    const styleValues = collectBoundValues(result, 'style=');
    const expandedValues = collectBoundValues(result, 'aria-expanded=');
    const titleValues = collectBoundValues(result, 'title=');

    expect(markup).toContain('Street');
    expect(markup).toContain('dc-field-nested-items');
    expect(styleValues).toEqual([nothing, '--nest-depth: 1']);
    expect(String(expandedValues[0])).toBe('true');
    expect(titleValues).toContain('Collapse');
    expect(markup).toContain('Collapse nested fields');
  });

  it('supports expansion for arrays of objects but not primitive arrays', () => {
    const arrayObjectResult = renderSchemaFieldListItem(
      arrayObjectField,
      0,
      new Set(),
      null,
      vi.fn(),
      vi.fn(),
    );
    const arrayPrimitiveResult = renderSchemaFieldListItem(
      arrayField,
      0,
      new Set(),
      null,
      vi.fn(),
      vi.fn(),
    );

    expect(templateToMarkup(arrayObjectResult)).toContain('dc-field-expand-btn');
    expect(collectBoundValues(arrayObjectResult, '@click=')).toHaveLength(2);

    expect(templateToMarkup(arrayPrimitiveResult)).toContain('dc-field-expand-spacer');
    expect(templateToMarkup(arrayPrimitiveResult)).not.toContain('dc-field-expand-btn');
    expect(collectBoundValues(arrayPrimitiveResult, '@click=')).toHaveLength(1);
  });
});
