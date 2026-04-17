/**
 * BorderInput — Lit component for per-side border editing.
 *
 * Manages linked/unlinked state internally. When linked, a single row
 * controls all four sides. When unlinked, four rows allow independent editing.
 *
 * Dispatches a 'change' CustomEvent with a BorderValue payload when any
 * border property changes.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import {
  type BorderValue,
  type BorderSideValue,
  areBorderSidesEqual,
  parseValueWithUnit,
  formatValueWithUnit,
  nearestSpacingStep,
  DEFAULT_SPACING_UNIT,
} from './style-inputs.js';

const EMPTY_SIDE: BorderSideValue = { width: '', style: 'solid', color: '' };

const BORDER_STYLES = [
  { label: 'None', value: 'none' },
  { label: 'Solid', value: 'solid' },
  { label: 'Dashed', value: 'dashed' },
  { label: 'Dotted', value: 'dotted' },
];

const SIDES = ['top', 'right', 'bottom', 'left'] as const;

@customElement('epistola-border-input')
export class BorderInput extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ type: Object }) value?: BorderValue;
  @property({ type: Array }) units: string[] = ['pt', 'sp'];
  @property({ type: Boolean }) readOnly = false;

  @state() private _linked = true;

  override willUpdate(changed: Map<string, unknown>) {
    if (changed.has('value') && this.value) {
      // If sides differ, force unlinked
      if (!areBorderSidesEqual(this.value)) {
        this._linked = false;
      }
    }
  }

  private get _parsed(): BorderValue {
    return (
      this.value ?? {
        top: { ...EMPTY_SIDE },
        right: { ...EMPTY_SIDE },
        bottom: { ...EMPTY_SIDE },
        left: { ...EMPTY_SIDE },
      }
    );
  }

  private get _defaultUnit(): string {
    return this.units[0] ?? 'pt';
  }

  private _emitChange(value: BorderValue) {
    this.dispatchEvent(new CustomEvent('change', { detail: value }));
  }

  private _handleSideChange(side: keyof BorderValue, field: keyof BorderSideValue, val: string) {
    const parsed = this._parsed;
    const updated = { ...parsed[side], [field]: val };
    if (this._linked) {
      this._emitChange({
        top: { ...updated },
        right: { ...updated },
        bottom: { ...updated },
        left: { ...updated },
      });
    } else {
      this._emitChange({ ...parsed, [side]: updated });
    }
  }

  private _handleToggle() {
    if (this._linked) {
      this._linked = false;
    } else {
      this._linked = true;
      const top = this._parsed.top;
      this._emitChange({
        top: { ...top },
        right: { ...top },
        bottom: { ...top },
        left: { ...top },
      });
    }
  }

  override render() {
    return html`
      <div class="style-border-input">
        <div class="style-border-header">
          <button
            type="button"
            class="style-border-link-toggle ${this._linked ? 'linked' : ''}"
            title=${this._linked ? 'Edit sides independently' : 'Apply to all sides'}
            ?disabled=${this.readOnly}
            @click=${this._handleToggle}
          >
            ⊞
          </button>
        </div>
        ${this._linked
          ? this._renderSideRow('top', 'All')
          : SIDES.map((side) => this._renderSideRow(side, side[0].toUpperCase()))}
      </div>
    `;
  }

  private _renderSideRow(side: (typeof SIDES)[number], label: string) {
    const s = this._parsed[side];
    const widthParsed = parseValueWithUnit(s.width, this._defaultUnit);

    return html`
      <div class="style-border-side-group">
        <div class="style-border-row">
          <span class="style-border-label">${label}</span>
          <input
            type="number"
            class="ep-input style-border-width"
            min="0"
            step="0.5"
            .value=${String(widthParsed.value || '')}
            ?disabled=${this.readOnly}
            @change=${(e: Event) => {
              const num = parseFloat((e.target as HTMLInputElement).value) || 0;
              this._handleSideChange(
                side,
                'width',
                num > 0 ? formatValueWithUnit(num, widthParsed.unit) : '',
              );
            }}
          />
          ${this.units.length > 1
            ? html`
                <select
                  class="ep-select style-border-unit-select"
                  ?disabled=${this.readOnly}
                  @change=${(e: Event) => {
                    const newUnit = (e.target as HTMLSelectElement).value;
                    const oldUnit = widthParsed.unit;
                    let newValue = widthParsed.value;
                    if (oldUnit === 'pt' && newUnit === 'sp') {
                      newValue = parseFloat(
                        nearestSpacingStep(widthParsed.value, DEFAULT_SPACING_UNIT),
                      );
                    } else if (oldUnit === 'sp' && newUnit === 'pt') {
                      newValue = widthParsed.value * DEFAULT_SPACING_UNIT;
                    }
                    this._handleSideChange(
                      side,
                      'width',
                      newValue > 0 ? formatValueWithUnit(newValue, newUnit) : '',
                    );
                  }}
                >
                  ${this.units.map(
                    (u) =>
                      html`<option .value=${u} ?selected=${u === widthParsed.unit}>${u}</option>`,
                  )}
                </select>
              `
            : nothing}
        </div>
        <div class="style-border-row">
          <span class="style-border-label"></span>
          <select
            class="ep-select style-border-style-select"
            ?disabled=${this.readOnly}
            @change=${(e: Event) =>
              this._handleSideChange(side, 'style', (e.target as HTMLSelectElement).value)}
          >
            ${BORDER_STYLES.map(
              (opt) =>
                html`<option .value=${opt.value} ?selected=${s.style === opt.value}>
                  ${opt.label}
                </option>`,
            )}
          </select>
          <input
            type="color"
            class="style-border-color-picker"
            .value=${s.color && s.color.startsWith('#') ? s.color : '#000000'}
            ?disabled=${this.readOnly}
            @input=${(e: Event) =>
              this._handleSideChange(side, 'color', (e.target as HTMLInputElement).value)}
          />
          <input
            type="text"
            class="ep-input style-border-color-text"
            .value=${s.color || ''}
            placeholder="#000000"
            ?disabled=${this.readOnly}
            @change=${(e: Event) =>
              this._handleSideChange(side, 'color', (e.target as HTMLInputElement).value)}
          />
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-border-input': BorderInput;
  }
}
