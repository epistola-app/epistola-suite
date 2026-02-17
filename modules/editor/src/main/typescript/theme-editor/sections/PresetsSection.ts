/**
 * PresetsSection — List of block style presets with Add/Remove.
 *
 * Each preset is an expandable card rendered by PresetItem.
 */

import { html } from 'lit'
import type { ThemeEditorState } from '../ThemeEditorState.js'
import { renderPresetItem } from './PresetItem.js'

export function renderPresetsSection(
  state: ThemeEditorState,
  expandedPresets: Set<string>,
  onTogglePreset: (name: string) => void,
): unknown {
  const presets = state.theme.blockStylePresets
  const entries = Object.entries(presets)

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Block Style Presets</h3>
      <p class="theme-section-hint">
        Named style collections that blocks can reference. Similar to CSS classes.
      </p>

      <div class="presets-list">
        ${entries.length === 0
          ? html`<div class="presets-empty">No presets defined. Click "Add Preset" to create one.</div>`
          : entries.map(([name, preset]) =>
              renderPresetItem(
                state,
                name,
                preset,
                expandedPresets.has(name),
                () => onTogglePreset(name),
                () => state.removePreset(name),
              ),
            )
        }
      </div>

      <button
        class="ep-btn-outline preset-add-btn"
        @click=${() => {
          const name = generatePresetName(presets)
          state.addPreset(name)
          expandedPresets.add(name)
        }}
      >+ Add Preset</button>
    </section>
  `
}

function generatePresetName(presets: Record<string, unknown>): string {
  let i = 1
  while (presets[`preset-${i}`]) i++
  return `preset-${i}`
}
