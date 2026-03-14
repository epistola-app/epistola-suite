/**
 * DocumentStylesSection — Inheritable style properties from the style system.
 *
 * Reuses the same pattern as EpistolaInspector._renderDocumentStyleGroups():
 * iterates defaultStyleFieldGroups, filters inheritable fields, renders
 * with the appropriate input function from style-inputs.ts.
 */

import { html, nothing } from 'lit'
import type { StyleField } from '@epistola/template-model/generated/style-system.js'
import { defaultStyleFieldGroups, isStyleFieldInheritable } from '../../engine/style-fields.js'
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
} from '../../ui/inputs/style-inputs.js'
import type { ThemeEditorState } from '../ThemeEditorState.js'

export function renderDocumentStylesSection(state: ThemeEditorState): unknown {
  const docStyles = state.theme.documentStyles

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Document Styles</h3>
      <p class="theme-section-hint">
        Default styles inherited by all templates using this theme.
      </p>
      ${defaultStyleFieldGroups.map(group => {
        const inheritableFields = group.fields.filter(f => isStyleFieldInheritable(f.key))
        if (inheritableFields.length === 0) return nothing

        return html`
          <div class="inspector-style-group">
            <div class="inspector-style-group-label">${group.label}</div>
            ${inheritableFields.map(field => renderStyleField(
              field,
              docStyles[field.key],
              (value) => state.updateDocumentStyle(field.key, value),
            ))}
          </div>
        `
      })}
    </section>
  `
}

function renderStyleField(
  field: StyleField,
  value: unknown,
  onChange: (value: unknown) => void,
): unknown {
  return html`
    <div class="inspector-field">
      <label class="inspector-field-label">${field.label}</label>
      ${renderStyleInput(field, value, onChange)}
    </div>
  `
}

function renderStyleInput(
  field: StyleField,
  value: unknown,
  onChange: (value: unknown) => void,
): unknown {
  switch (field.control) {
    case 'select':
      return renderSelectInput(
        value,
        field.options ?? [],
        (v) => onChange(v || undefined),
      )
    case 'unit':
      return renderUnitInput(
        value,
        field.units ?? ['px'],
        (v) => onChange(v),
      )
    case 'color':
      return renderColorInput(
        value,
        (v) => onChange(v || undefined),
      )
    case 'spacing':
      return renderSpacingInput(
        field.key,
        value,
        field.units ?? ['px'],
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
