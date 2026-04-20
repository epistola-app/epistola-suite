/**
 * PageSettingsSection — Page format, orientation, margins, background color.
 *
 * Reuses the same pattern as EpistolaInspector._renderPageSettings().
 */

import { html } from 'lit';
import { renderColorInput, DEFAULT_SPACING_UNIT } from '../../ui/inputs/style-inputs.js';
import type { ThemeEditorState } from '../ThemeEditorState.js';

const DEFAULT_MARGINS = { top: 20, right: 20, bottom: 20, left: 20 };

export function renderPageSettingsSection(state: ThemeEditorState, readOnly = false): unknown {
  const settings = state.theme.pageSettings;
  const format = settings?.format ?? 'A4';
  const orientation = settings?.orientation ?? 'portrait';
  const margins = settings?.margins ?? DEFAULT_MARGINS;
  const backgroundColor =
    ((settings as Record<string, unknown> | undefined)?.backgroundColor as string | undefined) ??
    '';

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Page Settings</h3>

      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-page-format">Format</label>
        <select
          id="theme-page-format"
          class="ep-select"
          ?disabled=${readOnly}
          @change=${(e: Event) =>
            state.updatePageSetting('format', (e.target as HTMLSelectElement).value)}
        >
          ${['A4', 'Letter', 'Custom'].map(
            (f) => html` <option .value=${f} ?selected=${format === f}>${f}</option> `,
          )}
        </select>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-page-orientation">Orientation</label>
        <select
          id="theme-page-orientation"
          class="ep-select"
          ?disabled=${readOnly}
          @change=${(e: Event) =>
            state.updatePageSetting('orientation', (e.target as HTMLSelectElement).value)}
        >
          ${['portrait', 'landscape'].map(
            (o) => html`
              <option .value=${o} ?selected=${orientation === o}>
                ${o[0].toUpperCase() + o.slice(1)}
              </option>
            `,
          )}
        </select>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-page-margin-top">Margins (mm)</label>
        <div class="inspector-margins-grid">
          ${(['top', 'right', 'bottom', 'left'] as const).map(
            (side) => html`
              <div class="inspector-margin-field">
                <span class="style-spacing-label">${side[0].toUpperCase()}</span>
                <input
                  type="number"
                  id=${`theme-page-margin-${side}`}
                  class="ep-input style-spacing-number"
                  .value=${String(margins[side])}
                  ?disabled=${readOnly}
                  @change=${(e: Event) =>
                    state.updateMargin(side, Number((e.target as HTMLInputElement).value))}
                />
              </div>
            `,
          )}
        </div>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-page-background-color">
          Background Color
        </label>
        ${renderColorInput(
          backgroundColor,
          (value) => state.updatePageSetting('backgroundColor', value || undefined),
          'theme-page-background-color',
          readOnly,
        )}
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label" for="theme-spacing-unit">Spacing Unit (pt)</label>
        <p class="theme-section-hint">
          Base unit for the spacing scale. All sp values are multiples of this.
        </p>
        <input
          type="number"
          class="ep-input"
          id="theme-spacing-unit"
          min="1"
          max="16"
          step="0.5"
          .value=${String(state.theme.spacingUnit ?? DEFAULT_SPACING_UNIT)}
          ?disabled=${readOnly}
          @change=${(e: Event) => {
            const val = parseFloat((e.target as HTMLInputElement).value);
            if (val >= 1 && val <= 16) {
              state.updateSpacingUnit(val === DEFAULT_SPACING_UNIT ? null : val);
            }
          }}
        />
      </div>
    </section>
  `;
}
