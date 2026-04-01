import { LitElement, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { unsafeSVG } from 'lit/directives/unsafe-svg.js';
import QRCode from 'qrcode';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { NodeId } from '../../types/index.js';
import { evaluateExpression } from '../../engine/resolve-expression.js';

const DEFAULT_SIZE_PT = 120;
const PX_PER_PT = 96 / 72;
const SPACING_UNIT_PT = 4;
const MAX_VALUE_BYTES = 2500;

function parseSizeToPt(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) && value > 0 ? value : null;
  }

  if (typeof value !== 'string') return null;

  const trimmed = value.trim();
  if (!trimmed) return null;

  if (trimmed.endsWith('sp')) {
    const sp = Number.parseFloat(trimmed.slice(0, -2));
    return Number.isFinite(sp) && sp > 0 ? sp * SPACING_UNIT_PT : null;
  }

  if (trimmed.endsWith('pt')) {
    const pt = Number.parseFloat(trimmed.slice(0, -2));
    return Number.isFinite(pt) && pt > 0 ? pt : null;
  }

  const unitless = Number.parseFloat(trimmed);
  return Number.isFinite(unitless) && unitless > 0 ? unitless : null;
}

function resolveCssSize(value: unknown): string {
  const sizePt = parseSizeToPt(value) ?? DEFAULT_SIZE_PT;
  return `${sizePt}pt`;
}

function resolveQrWidth(value: unknown): number {
  const sizePt = parseSizeToPt(value) ?? DEFAULT_SIZE_PT;
  return Math.max(128, Math.round(sizePt * PX_PER_PT));
}

function toScalarString(value: unknown): string | null {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }

  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }

  return null;
}

@customElement('epistola-qrcode-preview')
export class EpistolaQrCodePreview extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property() nodeId = '';
  @property() expression = '';
  @property() size = `${DEFAULT_SIZE_PT}pt`;

  @state() private _svgMarkup: string | null = null;
  @state() private _status: 'idle' | 'loading' | 'ready' | 'empty' | 'invalid' | 'too-long' =
    'idle';

  private _refreshToken = 0;
  private _unsubExample?: () => void;

  override connectedCallback(): void {
    super.connectedCallback();
    this._subscribeToEngine();
    void this._refresh();
  }

  override disconnectedCallback(): void {
    this._unsubExample?.();
    this._unsubExample = undefined;
    super.disconnectedCallback();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine')) {
      this._subscribeToEngine();
    }

    if (
      changed.has('engine') ||
      changed.has('nodeId') ||
      changed.has('expression') ||
      changed.has('size')
    ) {
      void this._refresh();
    }
  }

  override render() {
    const size = resolveCssSize(this.size);

    if (this._status === 'ready' && this._svgMarkup) {
      return html`
        <div
          class="qrcode-preview qrcode-preview-ready"
          style=${`width: ${size}; height: ${size};`}
        >
          ${unsafeSVG(this._svgMarkup)}
        </div>
      `;
    }

    const message =
      this._status === 'loading'
        ? 'Generating QR code...'
        : this._status === 'too-long'
          ? `Value too long for QR code (max ${MAX_VALUE_BYTES.toLocaleString()} characters)`
          : this._status === 'invalid'
            ? 'Expression must resolve to text, number, or boolean'
            : 'Add an expression and example data to preview';

    return html`
      <div
        class="qrcode-preview qrcode-preview-placeholder"
        style=${`width: ${size}; height: ${size};`}
      >
        <span class="qrcode-preview-placeholder-text">${message}</span>
      </div>
    `;
  }

  private _subscribeToEngine(): void {
    this._unsubExample?.();
    this._unsubExample = undefined;

    if (!this.engine) return;

    this._unsubExample = this.engine.events.on('example:change', () => {
      void this._refresh();
    });
  }

  private async _refresh(): Promise<void> {
    const expression = this.expression.trim();
    if (!this.engine || !this.nodeId || !expression) {
      this._svgMarkup = null;
      this._status = 'empty';
      return;
    }

    const token = ++this._refreshToken;
    this._status = 'loading';

    const evaluationContext = this.engine.getEvaluationContextAt(this.nodeId as NodeId);
    const resolved = await evaluateExpression(expression, evaluationContext);

    if (token !== this._refreshToken) return;

    const qrValue = toScalarString(resolved);
    if (!qrValue) {
      this._svgMarkup = null;
      this._status = 'invalid';
      return;
    }

    if (new TextEncoder().encode(qrValue).byteLength > MAX_VALUE_BYTES) {
      this._svgMarkup = null;
      this._status = 'too-long';
      return;
    }

    try {
      this._svgMarkup = await QRCode.toString(qrValue, {
        type: 'svg',
        errorCorrectionLevel: 'M',
        margin: 1,
        width: resolveQrWidth(this.size),
        color: {
          dark: '#111827ff',
          light: '#ffffffff',
        },
      });
      this._status = 'ready';
    } catch {
      this._svgMarkup = null;
      this._status = 'invalid';
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-qrcode-preview': EpistolaQrCodePreview;
  }
}
