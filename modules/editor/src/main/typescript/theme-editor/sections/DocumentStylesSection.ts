/**
 * DocumentStylesSection — Inheritable style properties from the style registry.
 *
 * Reuses the same pattern as EpistolaInspector._renderDocumentStyleGroups():
 * iterates defaultStyleRegistry groups, filters inheritable props, renders
 * with the appropriate input function from style-inputs.ts.
 */

import { html, nothing } from 'lit';
import type { StyleProperty } from '@epistola.app/editor-model/generated/style-registry';
import { defaultStyleRegistry } from '../../engine/style-registry.js';
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
} from '../../ui/inputs/style-inputs.js';
import type { ThemeEditorState } from '../ThemeEditorState.js';

export function renderDocumentStylesSection(state: ThemeEditorState): unknown {
  const docStyles = state.theme.documentStyles;

  return html`
    <section class="theme-section">
      <h3 class="theme-section-label">Document Styles</h3>
      <p class="theme-section-hint">Default styles inherited by all templates using this theme.</p>
      ${defaultStyleRegistry.groups.map((group) => {
        const inheritableProps = group.properties.filter((p) => p.inheritable);
        if (inheritableProps.length === 0) return nothing;

        return html`
          <div class="inspector-style-group">
            <div class="inspector-style-group-label">${group.label}</div>
            ${inheritableProps.map((prop) =>
              renderStyleProperty(prop, docStyles[prop.key], (value) =>
                state.updateDocumentStyle(prop.key, value),
              ),
            )}
          </div>
        `;
      })}
    </section>
  `;
}

function renderStyleProperty(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
): unknown {
  const inputId = `theme-doc-style-${prop.key}`;
  return html`
    <div class="inspector-field">
      <label class="inspector-field-label" for=${inputId}>${prop.label}</label>
      ${renderStyleInput(prop, value, onChange, inputId)}
    </div>
  `;
}

function renderStyleInput(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
  inputId: string,
): unknown {
  switch (prop.type) {
    case 'select':
      return renderSelectInput(value, prop.options ?? [], (v) => onChange(v || undefined), inputId);
    case 'unit':
      return renderUnitInput(value, prop.units ?? ['px'], (v) => onChange(v), undefined, inputId);
    case 'color':
      return renderColorInput(value, (v) => onChange(v || undefined), inputId);
    case 'spacing':
      return renderSpacingInput(
        value,
        prop.units ?? ['px'],
        (v) => onChange(v),
        undefined,
        inputId,
      );
    default:
      return html`
        <input
          type="text"
          class="ep-input"
          id=${inputId}
          .value=${String(value ?? '')}
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value || undefined)}
        />
      `;
  }
}
