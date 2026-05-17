/**
 * `fontFamily` value normalization.
 *
 * The font `<select>` carries strings; its option *value* is the canonical
 * JSON encoding of the `{ slug, catalogKey }` reference. The backend
 * (`StyleApplicator.parseFontRef`) reads `fontFamily` as a structured
 * *object*, so the inspector normalizes the select's string back into that
 * object before storing it in the document. Kept tiny and isolated so the
 * select round-trip stays in one place.
 */

export interface FontFamilyRef {
  slug: string;
  catalogKey: string;
}

/**
 * Normalize a raw `fontFamily` style change value.
 *
 * - The canonical JSON string from the select → the `{ slug, catalogKey }`
 *   object the backend reads.
 * - An already-structured object → passed through unchanged.
 * - Empty / unset → `undefined` (clears the style).
 * - Anything else → returned unchanged (forward-safe).
 */
export function normalizeFontFamilyValue(value: unknown): unknown {
  if (value == null || value === '') return undefined;
  if (typeof value === 'object') return value;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed === '') return undefined;
    if (trimmed.startsWith('{')) {
      try {
        const parsed = JSON.parse(trimmed) as Record<string, unknown>;
        if (typeof parsed.slug === 'string' && typeof parsed.catalogKey === 'string') {
          return { slug: parsed.slug, catalogKey: parsed.catalogKey } satisfies FontFamilyRef;
        }
      } catch {
        // fall through — return the raw string unchanged
      }
    }
  }
  return value;
}

/**
 * Encode a stored `fontFamily` value into the string the `<select>` uses to
 * match its option, so the dropdown shows the current selection. The object
 * form is re-encoded to its canonical JSON; strings pass through.
 */
export function fontFamilyValueToSelectValue(value: unknown): unknown {
  if (value && typeof value === 'object') {
    const rec = value as Record<string, unknown>;
    if (typeof rec.slug === 'string' && typeof rec.catalogKey === 'string') {
      return JSON.stringify({ slug: rec.slug, catalogKey: rec.catalogKey });
    }
  }
  return value;
}
