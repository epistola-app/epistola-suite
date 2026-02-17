/**
 * PresetItem — Single preset editing: name, label, applicableTo, style properties.
 *
 * Renders all style groups from the registry using the same input dispatch
 * pattern as the inspector. The preset is an expandable card.
 */

import { html, nothing } from 'lit'
import type { StyleProperty } from '@epistola/template-model/generated/style-registry.js'
import type { BlockStylePreset } from '@epistola/template-model/generated/theme.js'
import { defaultStyleRegistry } from '../../engine/style-registry.js'
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
} from '../../ui/inputs/style-inputs.js'
import type { ThemeEditorState } from '../ThemeEditorState.js'

/** Node types available for applicableTo multi-select. */
const NODE_TYPES = [
  { label: 'Text', value: 'text' },
  { label: 'Container', value: 'container' },
  { label: 'Columns', value: 'columns' },
  { label: 'Table', value: 'table' },
  { label: 'Conditional', value: 'conditional' },
  { label: 'Loop', value: 'loop' },
]

export function renderPresetItem(
  state: ThemeEditorState,
  name: string,
  preset: BlockStylePreset,
  expanded: boolean,
  onToggle: () => void,
  onRemove: () => void,
): unknown {
  return html`
    <div class="preset-card">
      <div class="preset-card-header" @click=${onToggle}>
        <span class="preset-card-toggle">${expanded ? '\u25BE' : '\u25B8'}</span>
        <span class="preset-card-name">${preset.label || name}</span>
        <span class="preset-card-key">${name}</span>
        <button
          class="preset-card-remove"
          title="Remove preset"
          @click=${(e: Event) => { e.stopPropagation(); onRemove() }}
        >&times;</button>
      </div>
      ${expanded ? renderPresetBody(state, name, preset) : nothing}
    </div>
  `
}

function renderPresetBody(
  state: ThemeEditorState,
  name: string,
  preset: BlockStylePreset,
): unknown {
  const styles = (preset.styles ?? {}) as Record<string, unknown>
  const applicableTo = preset.applicableTo ?? []

  return html`
    <div class="preset-card-body">
      <!-- Name (key) -->
      <div class="inspector-field">
        <label class="inspector-field-label">Key (ID)</label>
        <input
          type="text"
          class="ep-input mono"
          .value=${name}
          @change=${(e: Event) => {
            const newName = (e.target as HTMLInputElement).value.trim()
            if (newName && newName !== name) {
              state.renamePreset(name, newName)
            }
          }}
        />
      </div>

      <!-- Label -->
      <div class="inspector-field">
        <label class="inspector-field-label">Label</label>
        <input
          type="text"
          class="ep-input"
          .value=${preset.label}
          @change=${(e: Event) => state.updatePresetLabel(name, (e.target as HTMLInputElement).value)}
        />
      </div>

      <!-- Applicable To -->
      <div class="inspector-field">
        <label class="inspector-field-label">Applicable To</label>
        <div class="preset-applicable-to">
          ${NODE_TYPES.map(nt => html`
            <label class="preset-checkbox-label">
              <input
                type="checkbox"
                .checked=${applicableTo.includes(nt.value)}
                @change=${(e: Event) => {
                  const checked = (e.target as HTMLInputElement).checked
                  const current = preset.applicableTo ?? []
                  const updated = checked
                    ? [...current, nt.value]
                    : current.filter(t => t !== nt.value)
                  state.updatePresetApplicableTo(name, updated)
                }}
              />
              ${nt.label}
            </label>
          `)}
        </div>
        <span class="preset-hint">Leave all unchecked to apply to all node types.</span>
      </div>

      <!-- Style properties -->
      ${defaultStyleRegistry.groups.map(group => html`
        <div class="inspector-style-group">
          <div class="inspector-style-group-label">${group.label}</div>
          ${group.properties.map(prop => renderPresetStyleProperty(
            prop,
            styles[prop.key],
            (value) => state.updatePresetStyle(name, prop.key, value),
          ))}
        </div>
      `)}
    </div>
  `
}

function renderPresetStyleProperty(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
): unknown {
  return html`
    <div class="inspector-field">
      <label class="inspector-field-label">${prop.label}</label>
      ${renderPresetStyleInput(prop, value, onChange)}
    </div>
  `
}

function renderPresetStyleInput(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
): unknown {
  switch (prop.type) {
    case 'select':
      return renderSelectInput(
        value,
        prop.options ?? [],
        (v) => onChange(v || undefined),
      )
    case 'unit':
      return renderUnitInput(
        value,
        prop.units ?? ['px'],
        (v) => onChange(v),
      )
    case 'color':
      return renderColorInput(
        value,
        (v) => onChange(v || undefined),
      )
    case 'spacing':
      return renderSpacingInput(
        value,
        prop.units ?? ['px'],
        (v) => onChange(v),
      )
    default:
      return html`
        <input
          type="text"
          class="ep-input"
          .value=${String(value ?? '')}
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value || undefined)}
        />
      `
  }
}
