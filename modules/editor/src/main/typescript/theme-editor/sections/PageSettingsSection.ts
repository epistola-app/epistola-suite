/**
 * PageSettingsSection — Page format, orientation, margins, background color.
 *
 * Reuses the same pattern as EpistolaInspector._renderPageSettings().
 */

import { html } from 'lit'
import { renderColorInput } from '../../ui/inputs/style-inputs.js'
import type { ThemeEditorState } from '../ThemeEditorState.js'

const DEFAULT_MARGINS = { top: 20, right: 20, bottom: 20, left: 20 }

export function renderPageSettingsSection(state: ThemeEditorState): unknown {
  const settings = state.theme.pageSettings
  const format = settings?.format ?? 'A4'
  const orientation = settings?.orientation ?? 'portrait'
  const margins = settings?.margins ?? DEFAULT_MARGINS
  const backgroundColor = (settings as Record<string, unknown> | undefined)?.backgroundColor as string | undefined ?? ''

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Page Settings</h3>

      <div class="inspector-field">
        <label class="inspector-field-label">Format</label>
        <select
          class="ep-select"
          @change=${(e: Event) => state.updatePageSetting('format', (e.target as HTMLSelectElement).value)}
        >
          ${['A4', 'Letter', 'Custom'].map(f => html`
            <option .value=${f} ?selected=${format === f}>${f}</option>
          `)}
        </select>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label">Orientation</label>
        <select
          class="ep-select"
          @change=${(e: Event) => state.updatePageSetting('orientation', (e.target as HTMLSelectElement).value)}
        >
          ${['portrait', 'landscape'].map(o => html`
            <option .value=${o} ?selected=${orientation === o}>${o[0].toUpperCase() + o.slice(1)}</option>
          `)}
        </select>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label">Margins (mm)</label>
        <div class="inspector-margins-grid">
          ${(['top', 'right', 'bottom', 'left'] as const).map(side => html`
            <div class="inspector-margin-field">
              <span class="style-spacing-label">${side[0].toUpperCase()}</span>
              <input
                type="number"
                class="ep-input style-spacing-number"
                .value=${String(margins[side])}
                @change=${(e: Event) => state.updateMargin(side, Number((e.target as HTMLInputElement).value))}
              />
            </div>
          `)}
        </div>
      </div>

      <div class="inspector-field">
        <label class="inspector-field-label">Background Color</label>
        ${renderColorInput(
          backgroundColor,
          (value) => state.updatePageSetting('backgroundColor', value || undefined),
        )}
      </div>
    </section>
  `
}
