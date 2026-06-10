// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { render } from 'lit';
import { formatValue, migrationKey, renderMigrationDialog } from './MigrationAssistant.js';
import type { MigrationSuggestion } from '../utils/schemaMigration.js';

describe('formatValue', () => {
  it('null -> "null"', () => {
    expect(formatValue(null)).toBe('null');
  });

  it('undefined -> "undefined"', () => {
    expect(formatValue(undefined)).toBe('undefined');
  });

  it('string -> quoted', () => {
    expect(formatValue('hello')).toBe('"hello"');
  });

  it('number -> string', () => {
    expect(formatValue(42)).toBe('42');
    expect(formatValue(3.14)).toBe('3.14');
  });

  it('boolean -> string', () => {
    expect(formatValue(true)).toBe('true');
    expect(formatValue(false)).toBe('false');
  });

  it('single-paragraph doc -> "Rich text (inline)"', () => {
    expect(
      formatValue({
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Hi' }] }],
      }),
    ).toBe('Rich text (inline)');
  });

  it('multi-paragraph doc -> "Rich text (block)"', () => {
    expect(
      formatValue({
        type: 'doc',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: 'A' }] },
          { type: 'paragraph', content: [{ type: 'text', text: 'B' }] },
        ],
      }),
    ).toBe('Rich text (block)');
  });

  it('doc with bullet_list -> "Rich text (block)"', () => {
    expect(
      formatValue({
        type: 'doc',
        content: [{ type: 'paragraph' }, { type: 'bullet_list', content: [] }],
      }),
    ).toBe('Rich text (block)');
  });

  it('doc with marks -> "Rich text (inline)"', () => {
    expect(
      formatValue({
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: 'bold', marks: [{ type: 'strong' }] },
              {
                type: 'text',
                text: 'link',
                marks: [{ type: 'link', attrs: { href: 'https://x.com' } }],
              },
            ],
          },
        ],
      }),
    ).toBe('Rich text (inline)');
  });

  it('empty array -> "0 items"', () => {
    expect(formatValue([])).toBe('0 items');
  });

  it('single-item array -> "1 item"', () => {
    expect(formatValue([42])).toBe('1 item');
  });

  it('multi-item array -> pluralized', () => {
    expect(formatValue([1, 2, 3])).toBe('3 items');
  });

  it('plain object -> "Object"', () => {
    expect(formatValue({ name: 'John', age: 30 })).toBe('Object');
  });

  it('nested plain object -> "Object"', () => {
    expect(formatValue({ address: { street: 'Main', city: 'NYC' } })).toBe('Object');
  });
});

// ===========================================================================
// renderMigrationDialog — DOM rendering tests
// ===========================================================================

type Callbacks = Parameters<typeof renderMigrationDialog>[2];

function noop() {}

const stubCallbacks: Callbacks = {
  onApply: noop,
  onForceSave: noop,
  onCancel: noop,
  onToggleMigration: noop,
  onSelectAll: noop,
  onSelectNone: noop,
};

function makeMigration(overrides: Partial<MigrationSuggestion> = {}): MigrationSuggestion {
  return {
    exampleId: 'e1',
    exampleName: 'Example 1',
    path: '$.field1',
    issue: 'TYPE_MISMATCH',
    currentValue: 'hello',
    expectedType: 'integer',
    suggestedValue: null,
    autoMigratable: false,
    ...overrides,
  };
}

function renderDialog(
  migrations: MigrationSuggestion[],
  selected = new Set<string>(),
): HTMLElement {
  const div = document.createElement('div');
  const result = renderMigrationDialog(migrations, selected, stubCallbacks);
  render(result, div);
  return div;
}

describe('renderMigrationDialog', () => {
  it('returns nothing when no migrations', () => {
    const div = renderDialog([]);
    expect(div.textContent?.trim()).toBe('');
  });

  it('renders title', () => {
    const div = renderDialog([makeMigration()]);
    expect(div.querySelector('.dc-migration-title')?.textContent).toBe('Schema Changes Detected');
  });

  it('renders issue count in subtitle', () => {
    const div = renderDialog([makeMigration()]);
    const text = div.querySelector('.dc-migration-subtitle')?.textContent ?? '';
    expect(text).toContain('1 issue');
    expect(text).toContain('1 example');
  });

  it('renders plural subtitle for multiple issues', () => {
    const div = renderDialog([makeMigration(), makeMigration({ path: '$.field2' })]);
    const text = div.querySelector('.dc-migration-subtitle')?.textContent ?? '';
    expect(text).toContain('2 issues');
  });

  it('renders "None can be auto-fixed" when none are auto-migratable', () => {
    const div = renderDialog([makeMigration()]);
    const text = div.querySelector('.dc-migration-subtitle')?.textContent ?? '';
    expect(text).toContain('None can be auto-fixed');
  });

  it('renders auto-fixable count when applicable', () => {
    const div = renderDialog([makeMigration({ autoMigratable: true, suggestedValue: 'x' })]);
    const text = div.querySelector('.dc-migration-subtitle')?.textContent ?? '';
    expect(text).toContain('1 can be auto-fixed');
  });

  it('renders Select all / Select none buttons', () => {
    const div = renderDialog([makeMigration({ autoMigratable: true, suggestedValue: 'x' })]);
    expect(div.textContent).toContain('Select all');
    expect(div.textContent).toContain('Select none');
  });

  it('does not render select controls when nothing auto-migratable', () => {
    const div = renderDialog([makeMigration()]);
    expect(div.textContent).not.toContain('Select all');
  });

  it('renders Save Anyway and Cancel buttons', () => {
    const div = renderDialog([makeMigration()]);
    expect(div.textContent).toContain('Save Anyway');
    expect(div.textContent).toContain('Cancel');
  });

  it('renders Apply button when auto-migratable exists', () => {
    const div = renderDialog([makeMigration({ autoMigratable: true, suggestedValue: 'x' })]);
    expect(div.textContent).toContain('Apply');
  });

  it('renders TYPE_MISMATCH with current-to-expected conversion', () => {
    const div = renderDialog([makeMigration()]);
    const text = div.querySelector('.dc-migration-item-conversion')?.textContent ?? '';
    expect(text).toContain('"hello"');
    expect(text).toContain('integer');
  });

  it('renders TYPE_MISMATCH issue badge', () => {
    const div = renderDialog([makeMigration()]);
    expect(div.querySelector('.badge-warning')?.textContent?.trim()).toBe('Type mismatch');
  });

  it('renders MISSING_REQUIRED issue badge', () => {
    const div = renderDialog([
      makeMigration({
        issue: 'MISSING_REQUIRED',
        currentValue: undefined as unknown as string,
      }),
    ]);
    expect(div.querySelector('.badge-warning')?.textContent?.trim()).toBe('Missing required');
  });

  it('renders UNKNOWN_FIELD issue badge', () => {
    const div = renderDialog([makeMigration({ issue: 'UNKNOWN_FIELD' })]);
    expect(div.querySelector('.badge-warning')?.textContent?.trim()).toBe('Unknown field');
  });

  it('renders MISSING_REQUIRED expected type info', () => {
    const div = renderDialog([
      makeMigration({
        issue: 'MISSING_REQUIRED',
        currentValue: undefined as unknown as string,
        expectedType: 'string',
      }),
    ]);
    expect(div.querySelector('.dc-migration-item-info')?.textContent).toContain('string');
  });

  it('shows deselectable checkbox for auto-migratable item', () => {
    const m = makeMigration({ autoMigratable: true, suggestedValue: 'x' });
    const div = renderDialog([m], new Set([migrationKey(m)]));
    const checkbox = div.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(checkbox).not.toBeNull();
    expect(checkbox.checked).toBe(true);
  });

  it('shows non-auto-fix icon and "Save Anyway" when no auto-migratable', () => {
    const div = renderDialog([makeMigration()]);
    const icon = div.querySelector('.dc-migration-item-icon');
    expect(icon).not.toBeNull();
    expect(icon?.getAttribute('title')).toBe('Cannot be auto-fixed');
  });

  it('renders suggested value when auto-migratable', () => {
    const div = renderDialog([
      makeMigration({
        autoMigratable: true,
        suggestedValue: 'converted-value',
      }),
    ]);
    expect(div.querySelector('.dc-migration-suggested')?.textContent).toBe('"converted-value"');
  });
});
