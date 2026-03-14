/**
 * PresetItem — Single preset editing: name, label, applicableTo, style properties.
 *
 * Renders all style groups from the registry using the same input dispatch
 * pattern as the inspector. The preset is an expandable card.
 */

import { html, nothing } from 'lit'
import type { StyleField } from '@epistola/template-model/generated/style-system.js'
import type { BlockStylePreset } from '@epistola/template-model/generated/theme.js'
import { defaultStyleFieldGroups, readStyleFieldValue } from '../../engine/style-fields.js'
import {
  renderUnitInput,
  renderColorInput,
  renderSelectInput,
  renderBoxInput,
  type BoxLinkState,
  readBoxFromStyles,
  expandBoxToStyles,
} from '../../ui/inputs/style-inputs.js'
import type { BoxValue } from '../../engine/style-values.js'
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

// Track link modes for each preset's box inputs
const presetLinkModes: Map<string, Map<string, BoxLinkState>> = new Map()

function getPresetLinkModes(presetName: string): Map<string, BoxLinkState> {
  if (!presetLinkModes.has(presetName)) {
    presetLinkModes.set(presetName, new Map())
  }
  return presetLinkModes.get(presetName)!
}

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
      ${defaultStyleFieldGroups.map(group => html`
          <div class="inspector-style-group">
            <div class="inspector-style-group-label">${group.label}</div>
            ${group.fields.map(field => {
              const value = readStyleFieldValue(field.key, styles)
              return renderPresetStyleField(
                name,
                field,
                value,
                styles,
              (v) => state.updatePresetStyle(name, field.key, v),
            )
          })}
        </div>
      `)}
    </div>
  `
}

function renderPresetStyleField(
  presetName: string,
  field: StyleField,
  value: unknown,
  inlineStyles: Record<string, unknown>,
  onChange: (value: unknown) => void,
): unknown {
  return html`
    <div class="inspector-field">
      <label class="inspector-field-label">${field.label}</label>
      ${renderPresetStyleInput(presetName, field, value, inlineStyles, onChange)}
    </div>
  `
}

function renderPresetStyleInput(
  presetName: string,
  field: StyleField,
  value: unknown,
  inlineStyles: Record<string, unknown>,
  onChange: (value: unknown) => void,
): unknown {
  switch (field.control) {
    case 'select':
      return renderSelectInput(
        value,
        field.options ?? [],
        (v: string) => onChange(v || undefined),
      )
    case 'unit':
      return renderUnitInput(
        value,
        field.units ?? ['px'],
        (v: string) => onChange(v),
      )
    case 'color':
      return renderColorInput(
        value,
        (v: string) => onChange(v || undefined),
      )
    case 'spacing': {
      // Get the mapping for this spacing property (margin or padding)
      const prefix = field.key // 'margin' or 'padding'

      // Extract current box value from inline styles
      const boxValue = readBoxFromStyles(prefix, inlineStyles) ?? {
        top: undefined,
        right: undefined,
        bottom: undefined,
        left: undefined,
      }

      // For presets, use zero as default (no component defaults)
      const boxDefaults: BoxValue = {
        top: '0px',
        right: '0px',
        bottom: '0px',
        left: '0px',
      }

      // Get current link state for this field
      const linkModes = getPresetLinkModes(presetName)
      const linkState = linkModes.get(field.key) ?? { all: false, horizontal: false, vertical: false }

      return renderBoxInput({
        id: field.key,
        value: boxValue,
        defaults: boxDefaults,
        units: field.units ?? ['px'],
        linkState,
        onChange: (newValue) => {
          // Expand box value to individual style properties
          const styles = { ...inlineStyles }
          expandBoxToStyles(prefix, newValue, styles)
          onChange(styles)
        },
        onLinkStateChange: (newState) => {
          linkModes.set(field.key, newState)
        },
      })
    }
    case 'number':
      return html`
        <input
          type="number"
          class="ep-input"
          step="any"
          .value=${String(value ?? '')}
          @change=${(e: Event) => onChange(Number((e.target as HTMLInputElement).value))}
        />
      `
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
