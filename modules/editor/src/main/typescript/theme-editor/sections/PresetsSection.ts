/**
 * PresetsSection — List of block style presets with Add/Remove.
 *
 * Each preset is an expandable card rendered by PresetItem.
 * Uses native <details>/<summary> for expand/collapse.
 */

import { html } from 'lit';
import type { ThemeEditorState } from '../ThemeEditorState.js';
import { renderPresetItem } from './PresetItem.js';

export function renderPresetsSection(state: ThemeEditorState, readOnly = false): unknown {
  const presets = state.theme.blockStylePresets;
  const entries = Object.entries(presets);

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Block Style Presets</h3>
      <p class="theme-section-hint">
        Named style collections that blocks can reference. Similar to CSS classes.
      </p>

      <div class="theme-preset-list">
        ${entries.length === 0
          ? html`
              <div class="empty-state">
                <div class="empty-state-title">No presets defined</div>
                <div class="empty-state-description">Click "Add Preset" to create one.</div>
              </div>
            `
          : entries.map(([name, preset]) =>
              renderPresetItem(state, name, preset, () => state.removePreset(name), readOnly),
            )}
      </div>

      <button
        class="theme-preset-add-btn"
        ?disabled=${readOnly}
        @click=${() => {
          const name = generatePresetName(presets);
          state.addPreset(name);
        }}
      >
        + Add Preset
      </button>
    </section>
  `;
}

function generatePresetName(presets: Record<string, unknown>): string {
  let i = 1;
  while (presets[`preset-${i}`]) i++;
  return `preset-${i}`;
}
