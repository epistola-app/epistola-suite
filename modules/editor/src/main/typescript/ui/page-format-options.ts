// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/** Page formats the PDF renderer can produce. */
const RENDERABLE_PAGE_FORMATS = ['A4', 'Letter'] as const;

export interface PageFormatOption {
  value: string;
  label: string;
}

/**
 * The options for a page-format picker. The contract's `PageFormat` also lists `Custom`, but the
 * schema has no fields for its size and the renderer draws it as A4, so it is not offered (#1027).
 * A document that already stores `Custom` (or any other value) keeps it as the selected option,
 * labelled with what it actually renders as, so opening the picker never rewrites it silently.
 */
export function pageFormatOptions(current: string | undefined): PageFormatOption[] {
  const options: PageFormatOption[] = RENDERABLE_PAGE_FORMATS.map((f) => ({ value: f, label: f }));
  if (current && !(RENDERABLE_PAGE_FORMATS as readonly string[]).includes(current)) {
    options.push({ value: current, label: `${current} (renders as A4)` });
  }
  return options;
}
