/**
 * PresetItem — Single preset editing: name, label, applicableTo, style properties.
 *
 * Renders all style groups from the registry using the same input dispatch
 * pattern as the inspector. The preset uses native <details>/<summary>
 * for expand/collapse, providing built-in accessibility.
 */

import { html } from 'lit';
import type { StyleProperty } from '@epistola.app/editor-model/generated/style-registry';
import type { BlockStylePreset } from '@epistola.app/editor-model/generated/theme';
import { defaultStyleRegistry } from '../../engine/style-registry.js';
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
  readSpacingFromStyles,
} from '../../ui/inputs/style-inputs.js';
import type { ThemeEditorState } from '../ThemeEditorState.js';

/** Node types available for applicableTo multi-select. */
const NODE_TYPES = [
  { label: 'Text', value: 'text' },
  { label: 'Container', value: 'container' },
  { label: 'Columns', value: 'columns' },
  { label: 'Table', value: 'table' },
  { label: 'Conditional', value: 'conditional' },
  { label: 'Loop', value: 'loop' },
];

export function renderPresetItem(
  state: ThemeEditorState,
  name: string,
  preset: BlockStylePreset,
  onRemove: () => void,
  readOnly = false,
): unknown {
  return html`
    <details class="theme-preset-card">
      <summary class="theme-preset-header" aria-label="${preset.label || name}">
        <span class="theme-preset-toggle" aria-hidden="true">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            class="lucide lucide-chevron-down-icon lucide-chevron-down"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </span>
        <span class="theme-preset-name">${preset.label || name}</span>
        <span class="theme-preset-key">${name}</span>
        <button
          class="theme-preset-remove"
          title="Remove preset"
          aria-label="Remove preset ${preset.label || name}"
          ?disabled=${readOnly}
          @click=${(e: Event) => {
            e.stopPropagation();
            onRemove();
          }}
        >
          &times;
        </button>
      </summary>
      ${renderPresetBody(state, name, preset, readOnly)}
    </details>
  `;
}

function renderPresetBody(
  state: ThemeEditorState,
  name: string,
  preset: BlockStylePreset,
  readOnly = false,
): unknown {
  const styles = (preset.styles ?? {}) as Record<string, unknown>;
  const applicableTo = preset.applicableTo ?? [];

  return html`
    <div class="theme-preset-body">
      <!-- Name (key) -->
      <div class="inspector-field">
        <label class="inspector-field-label" for="preset-key-${name}">Key (ID)</label>
        <input
          type="text"
          id="preset-key-${name}"
          class="ep-input mono"
          .value=${name}
          ?disabled=${readOnly}
          @change=${(e: Event) => {
            const newName = (e.target as HTMLInputElement).value.trim();
            if (newName && newName !== name) {
              state.renamePreset(name, newName);
            }
          }}
        />
      </div>

      <!-- Label -->
      <div class="inspector-field">
        <label class="inspector-field-label" for="preset-label-${name}">Label</label>
        <input
          type="text"
          id="preset-label-${name}"
          class="ep-input"
          .value=${preset.label}
          ?disabled=${readOnly}
          @change=${(e: Event) =>
            state.updatePresetLabel(name, (e.target as HTMLInputElement).value)}
        />
      </div>

      <!-- Applicable To -->
      <div class="inspector-field">
        <span class="inspector-field-label">Applicable To</span>
        <div class="theme-preset-applicable-to">
          ${NODE_TYPES.map(
            (nt) => html`
              <label class="theme-preset-checkbox-label">
                <input
                  type="checkbox"
                  id="preset-${name}-${nt.value}"
                  .checked=${applicableTo.includes(nt.value)}
                  ?disabled=${readOnly}
                  @change=${(e: Event) => {
                    const checked = (e.target as HTMLInputElement).checked;
                    const current = preset.applicableTo ?? [];
                    const updated = checked
                      ? [...current, nt.value]
                      : current.filter((t) => t !== nt.value);
                    state.updatePresetApplicableTo(name, updated);
                  }}
                />
                ${nt.label}
              </label>
            `,
          )}
        </div>
        <span class="theme-preset-hint">Leave all unchecked to apply to all node types.</span>
      </div>

      <!-- Style properties -->
      ${defaultStyleRegistry.groups.map(
        (group) => html`
          <div class="inspector-style-group">
            <div class="inspector-style-group-label">${group.label}</div>
            ${group.properties.map((prop) => {
              const value =
                prop.type === 'spacing'
                  ? readSpacingFromStyles(prop.key, styles, prop.units?.[0] ?? 'px')
                  : styles[prop.key];
              return renderPresetStyleProperty(
                prop,
                value,
                (v) => state.updatePresetStyle(name, prop.key, v),
                `preset-${name}-style-${prop.key}`,
                readOnly,
              );
            })}
          </div>
        `,
      )}
    </div>
  `;
}

function renderPresetStyleProperty(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
  inputId: string,
  readOnly = false,
): unknown {
  return html`
    <div class="inspector-field">
      <label class="inspector-field-label" for=${inputId}>${prop.label}</label>
      ${renderPresetStyleInput(prop, value, onChange, inputId, readOnly)}
    </div>
  `;
}

function renderPresetStyleInput(
  prop: StyleProperty,
  value: unknown,
  onChange: (value: unknown) => void,
  inputId: string,
  readOnly = false,
): unknown {
  switch (prop.type) {
    case 'select':
      return renderSelectInput(
        value,
        prop.options ?? [],
        (v) => onChange(v || undefined),
        inputId,
        readOnly,
      );
    case 'unit':
      return renderUnitInput(
        value,
        prop.units ?? ['px'],
        (v) => onChange(v),
        undefined,
        inputId,
        readOnly,
      );
    case 'color':
      return renderColorInput(value, (v) => onChange(v || undefined), inputId, readOnly);
    case 'spacing':
      return renderSpacingInput(
        value,
        prop.units ?? ['px'],
        (v) => onChange(v),
        undefined,
        inputId,
        readOnly,
      );
    default:
      return html`
        <input
          type="text"
          class="ep-input"
          id=${inputId}
          .value=${String(value ?? '')}
          ?disabled=${readOnly}
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value || undefined)}
        />
      `;
  }
}
