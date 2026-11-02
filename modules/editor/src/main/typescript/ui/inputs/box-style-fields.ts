// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Inspector fields for one box in a style map: a table cell (`cellStyles`) or a column
 * (`columnStyles`). Contract `2.0.0-alpha.3` allows the same four styles for both:
 * `backgroundColor`, `padding`, `border` and `verticalAlign`.
 */

import { html } from 'lit';
import {
  renderColorInput,
  renderSelectInput,
  renderSpacingInput,
  readBorderFromStyles,
  expandBorderToStyles,
  readSpacingFromStyles,
  expandSpacingToStyles,
  type BorderValue,
  type SpacingValue,
} from './style-inputs.js';
import './BorderInput.js';

export type BoxStyles = Record<string, unknown>;

/** Mutates a copy of one box's styles; the caller writes the result back to its style map. */
export type BoxStyleUpdate = (mutate: (styles: BoxStyles) => void) => void;

export const VERTICAL_ALIGN_OPTIONS = [
  { label: 'Top', value: 'top' },
  { label: 'Middle', value: 'middle' },
  { label: 'Bottom', value: 'bottom' },
];

/** The four fields for one box, writing per-side keys as the rest of the inspector does. */
export function renderBoxStyleFields(
  styles: BoxStyles,
  update: BoxStyleUpdate,
  idPrefix: string,
): unknown {
  const set = (key: string, value: unknown) =>
    update((s) => {
      if (value === undefined || value === '') delete s[key];
      else s[key] = value;
    });

  return html`
    <div class="inspector-field">
      <label class="inspector-field-label">Background</label>
      ${renderColorInput(styles.backgroundColor as string | undefined, (v) =>
        set('backgroundColor', v || undefined),
      )}
    </div>
    <div class="inspector-field">
      <label class="inspector-field-label" for=${`${idPrefix}-padding`}>Padding</label>
      ${renderSpacingInput(
        readSpacingFromStyles('padding', styles),
        ['pt', 'sp'],
        (value: SpacingValue) => update((s) => expandSpacingToStyles('padding', value, s)),
        undefined,
        `${idPrefix}-padding`,
      )}
    </div>
    <div class="inspector-field">
      <label class="inspector-field-label">Border</label>
      <epistola-border-input
        .value=${readBorderFromStyles(styles)}
        .units=${['pt', 'sp']}
        @change=${(e: CustomEvent) =>
          update((s) => expandBorderToStyles(e.detail as BorderValue, s))}
      ></epistola-border-input>
    </div>
    <div class="inspector-field">
      <label class="inspector-field-label" for=${`${idPrefix}-vertical-align`}
        >Vertical Align</label
      >
      ${renderSelectInput(
        styles.verticalAlign as string | undefined,
        VERTICAL_ALIGN_OPTIONS,
        (v) => set('verticalAlign', v || undefined),
        `${idPrefix}-vertical-align`,
      )}
    </div>
  `;
}

/**
 * The canvas CSS for one box's styles: background, per-side padding and border, and the vertical
 * position of its content (a flex column, so `justify-content` moves the content down).
 */
export function boxStylesToCss(
  styles: BoxStyles | undefined,
  toCss: (value: string) => string,
): Record<string, string> {
  const css: Record<string, string> = {};
  if (!styles) return css;
  if (typeof styles.backgroundColor === 'string') css['background-color'] = styles.backgroundColor;
  for (const side of ['Top', 'Right', 'Bottom', 'Left']) {
    const padding = styles[`padding${side}`];
    if (typeof padding === 'string') css[`padding-${side.toLowerCase()}`] = toCss(padding);
    const border = styles[`border${side}`];
    if (typeof border === 'string') css[`border-${side.toLowerCase()}`] = toCss(border);
  }
  const justify = { top: 'flex-start', middle: 'center', bottom: 'flex-end' }[
    String(styles.verticalAlign)
  ];
  if (justify) {
    css.display = 'flex';
    css['flex-direction'] = 'column';
    css['justify-content'] = justify;
  }
  return css;
}
