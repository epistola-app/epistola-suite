/** @vitest-environment happy-dom */
import { describe, expect, it } from 'vitest';
import { createMenuElement } from './bubble-menu.js';
import { epistolaSchema } from './schema.js';
import { richTextBlockSchema } from './richTextBlockSchema.js';

/** Which rendered children are separators, in document order. */
function separatorMask(menuEl: HTMLElement): boolean[] {
  return Array.from(menuEl.children).map((c) => c.classList.contains('pm-bubble-sep'));
}

describe('bubble menu separators', () => {
  it('has no leading, trailing, or adjacent separators for the block value schema', () => {
    // richTextBlockSchema omits heading + expression, so those groups are empty.
    // Separators must not divide groups that were never rendered.
    const { menuEl } = createMenuElement(richTextBlockSchema);
    const mask = separatorMask(menuEl);

    expect(mask.at(0)).toBe(false);
    expect(mask.at(-1)).toBe(false);
    expect(mask.some((isSep, i) => isSep && mask[i + 1])).toBe(false);
  });

  it('divides groups on the full canvas schema without stray separators', () => {
    // Full schema has all four groups (marks, headings, lists, expression),
    // so separators appear between them but never lead/trail/double up.
    const { menuEl } = createMenuElement(epistolaSchema);
    const mask = separatorMask(menuEl);

    expect(mask.at(0)).toBe(false);
    expect(mask.at(-1)).toBe(false);
    expect(mask.some((isSep, i) => isSep && mask[i + 1])).toBe(false);
    // Four non-empty groups → exactly three dividers.
    expect(mask.filter(Boolean).length).toBe(3);
  });
});
