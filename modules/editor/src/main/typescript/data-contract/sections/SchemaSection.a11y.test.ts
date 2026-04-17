import { describe, expect, it } from 'vitest';
import { getDeleteFieldA11y } from './SchemaSection.js';

describe('getDeleteFieldA11y', () => {
  it('returns aria-describedby + hint when deleting is disabled', () => {
    const a11y = getDeleteFieldA11y('field-1', false);

    expect(a11y.title).toBe('A schema must contain at least one field');
    expect(a11y.ariaDescribedBy).toBe('dc-delete-hint-field-1');
    expect(a11y.showHint).toBe(true);
    expect(a11y.hintId).toBe('dc-delete-hint-field-1');
    expect(a11y.hintText).toBe('Delete is disabled. A schema must contain at least one field.');
  });

  it('returns no aria-describedby and no hint when deleting is allowed', () => {
    const a11y = getDeleteFieldA11y('field-1', true);

    expect(a11y.title).toBe('Delete field');
    expect(a11y.ariaDescribedBy).toBeNull();
    expect(a11y.showHint).toBe(false);
    expect(a11y.hintId).toBe('dc-delete-hint-field-1');
  });
});
