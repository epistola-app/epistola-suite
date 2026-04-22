import { nothing } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import { renderValidationMessages, renderValidationSummary } from './ValidationMessages.js';
import type { ValidationWarning } from './ValidationMessages.js';

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

describe('renderValidationMessages', () => {
  it('returns nothing for empty warnings', () => {
    expect(renderValidationMessages([])).toBe(nothing);
  });

  it('renders alert structure and singular schema warning details', () => {
    const warnings: ValidationWarning[] = [{ path: '$.name', message: 'is required' }];

    const result = renderValidationMessages(warnings);
    const markup = templateToMarkup(result);
    const source = isTemplateLike(result) ? result.strings.join('') : '';

    expect(source).toContain('role="alert"');
    expect(source).toContain('dc-validation-list');
    expect(markup).toContain('1 warning');
    expect(markup).toContain('Schema');
    expect(markup).toContain('$.name');
    expect(markup).toContain('is required');
  });

  it('renders plural counts and recent request metadata without raw suffixes', () => {
    const warnings: ValidationWarning[] = [
      { path: '$.name', message: 'is required' },
      {
        path: 'request:req_1234567890abcdef $.email',
        message: 'must be string [status=FAILED correlation=corr-7]',
      },
    ];

    const result = renderValidationMessages(warnings);
    const markup = templateToMarkup(result);

    expect(markup).toContain('2 warnings');
    expect(markup).toContain('Recent Request');
    expect(markup).toContain('req_1234…cdef');
    expect(markup).toContain('$.email');
    expect(markup).toContain('must be string');
    expect(markup).toContain('FAILED');
    expect(markup).toContain('corr-7');
    expect(markup).not.toContain('[status=FAILED correlation=corr-7]');
  });
});

describe('renderValidationSummary', () => {
  it('returns nothing for empty warnings', () => {
    expect(renderValidationSummary([], () => {})).toBe(nothing);
  });

  it('renders summary counts and wires the review callback', () => {
    const warnings: ValidationWarning[] = [
      { path: '$.name', message: 'is required' },
      { path: 'request:req_123 $.email', message: 'must be string' },
    ];
    const onReviewWarnings = vi.fn();

    const result = renderValidationSummary(warnings, onReviewWarnings);
    const markup = templateToMarkup(result);
    const source = isTemplateLike(result) ? result.strings.join('') : '';
    const clickHandlers = collectBoundValues(result, '@click=');

    expect(source).toContain('role="status"');
    expect(markup).toContain('Compatibility issues: 2 total');
    expect(markup).toContain('1 schema, 1 recent usage');
    expect(markup).toContain('Review Issues');
    expect(clickHandlers).toEqual([onReviewWarnings]);

    const reviewHandler = clickHandlers[0] as () => void;
    reviewHandler();

    expect(onReviewWarnings).toHaveBeenCalledTimes(1);
  });
});
