// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    <div class="theme-section theme-section-presets">
      <div class="theme-section-heading">
        <div>
          <h3 class="theme-section-label">Block Style Presets</h3>
          <p class="theme-section-hint">
            Reusable style collections that blocks can apply like CSS classes.
          </p>
        </div>
        <button
          class="ep-btn ep-btn-outline ep-btn-sm theme-preset-add-btn"
          ?disabled=${readOnly}
          @click=${(event: Event) => addAndOpenPreset(state, presets, event)}
        >
          Add preset
        </button>
      </div>

      <div class="theme-preset-list">
        ${
          entries.length === 0
            ? html`
                <div class="empty-state">
                  <div class="empty-state-title">No presets defined</div>
                  <div class="empty-state-description">
                    Add a preset to reuse the same styling across blocks.
                  </div>
                </div>
              `
            : entries.map(([name, preset]) =>
                renderPresetItem(state, name, preset, () => state.removePreset(name), readOnly),
              )
        }
      </div>
    </div>
  `;
}

function addAndOpenPreset(
  state: ThemeEditorState,
  presets: Record<string, unknown>,
  event: Event,
): void {
  const name = generatePresetName(presets);
  const editor = (event.currentTarget as HTMLElement).closest('epistola-theme-editor');
  state.addPreset(name);

  requestAnimationFrame(() => {
    const card = Array.from(
      editor?.querySelectorAll<HTMLDetailsElement>('.theme-preset-card') ?? [],
    ).find((item) => item.dataset.presetKey === name);
    if (!card) return;

    card.open = true;
    card.querySelector<HTMLInputElement>('.theme-preset-label-input')?.focus();
  });
}

function generatePresetName(presets: Record<string, unknown>): string {
  let i = 1;
  while (presets[`preset-${i}`]) i++;
  return `preset-${i}`;
}
