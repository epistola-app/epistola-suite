import { beforeEach, describe, expect, it, vi } from 'vitest';
import QRCode from 'qrcode';
import { evaluateExpression } from '../../engine/resolve-expression.js';
import { EpistolaQrCodePreview } from './EpistolaQrCodePreview.js';

vi.mock('qrcode', () => ({
  default: {
    toCanvas: vi.fn(),
    toString: vi.fn(),
  },
}));

vi.mock('../../engine/resolve-expression.js', () => ({
  evaluateExpression: vi.fn(),
}));

type FakeCanvasContext = {
  fillStyle: string;
  beginPath: () => void;
  roundRect?: (x: number, y: number, w: number, h: number, r: number) => void;
  fill: () => void;
  fillRect: (x: number, y: number, w: number, h: number) => void;
  drawImage: (img: unknown, x: number, y: number, w: number, h: number) => void;
};

type FakeCanvas = {
  width: number;
  height: number;
  getContext: (type: string) => FakeCanvasContext | null;
  toDataURL: (type: string) => string;
};

function createEngineMock() {
  return {
    getEvaluationContextAt: vi.fn(() => ({ data: {} })),
    events: {
      on: vi.fn(() => () => undefined),
    },
  };
}

function templateToHtml(value: unknown): string {
  if (value == null || value === false) return '';
  if (typeof value === 'symbol') return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(templateToHtml).join('');
  if (typeof value === 'object' && 'strings' in value && 'values' in value) {
    const template = value as { strings: ArrayLike<string>; values: unknown[] };
    return Array.from(template.strings)
      .map(
        (part, index) =>
          part + (index < template.values.length ? templateToHtml(template.values[index]) : ''),
      )
      .join('');
  }
  return '';
}

async function refreshPreview(el: EpistolaQrCodePreview): Promise<void> {
  el.updated(
    new Map<string, unknown>([
      ['engine', undefined],
      ['nodeId', undefined],
      ['expression', undefined],
      ['size', undefined],
      ['qrType', undefined],
      ['logoSrc', undefined],
    ]),
  );
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

function installMockImage(result: 'load' | 'error'): void {
  class MockImage {
    crossOrigin = '';
    private _srcSet = false;
    private _onload: null | (() => void) = null;
    private _onerror: null | (() => void) = null;

    set onload(fn: null | (() => void)) {
      this._onload = fn;
      this.notifyIfReady();
    }

    get onload() {
      return this._onload;
    }

    set onerror(fn: null | (() => void)) {
      this._onerror = fn;
      this.notifyIfReady();
    }

    get onerror() {
      return this._onerror;
    }

    set src(_value: string) {
      this._srcSet = true;
      this.notifyIfReady();
    }

    private notifyIfReady() {
      if (!this._srcSet) return;
      if (result === 'load') this._onload?.();
      if (result === 'error') this._onerror?.();
    }
  }

  (globalThis as { Image?: unknown }).Image = MockImage;
}

describe('EpistolaQrCodePreview', () => {
  let canvas: FakeCanvas;
  let ctx: FakeCanvasContext;

  beforeEach(() => {
    vi.clearAllMocks();

    ctx = {
      fillStyle: '',
      beginPath: vi.fn(),
      roundRect: vi.fn(),
      fill: vi.fn(),
      fillRect: vi.fn(),
      drawImage: vi.fn(),
    };

    canvas = {
      width: 160,
      height: 160,
      getContext: vi.fn(() => ctx),
      toDataURL: vi.fn(() => 'data:image/png;base64,fake'),
    };

    (globalThis as { document?: unknown }).document = {
      createElement: vi.fn((tag: string) => (tag === 'canvas' ? canvas : null)),
    };

    (globalThis as { window?: unknown }).window = {
      setTimeout: vi.fn(() => 1),
      clearTimeout: vi.fn(),
    };
  });

  it('renders placeholder for empty input state', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = '   ';
    el.nodeId = 'node-1';
    el.engine = createEngineMock() as never;

    await refreshPreview(el);
    const html = templateToHtml(el.render());

    expect(html).toContain('qrcode-preview-placeholder');
    expect(html).toContain('Add an expression and example data to preview');
  });

  it('sets invalid state when expression resolves non-scalar', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'customer';
    el.nodeId = 'node-1';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue({ complex: true });

    await refreshPreview(el);

    expect(templateToHtml(el.render())).toContain(
      'Expression must resolve to text, number, or boolean',
    );
  });

  it('sets too-long state when value exceeds qr byte limit', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'value';
    el.nodeId = 'node-1';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('x'.repeat(2501));

    await refreshPreview(el);

    expect(templateToHtml(el.render())).toContain('Value too long for QR code');
  });

  it('renders svg output in standard mode', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'value';
    el.nodeId = 'node-1';
    el.size = '20sp';
    el.qrType = 'standard';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('https://example.com');
    vi.mocked(QRCode.toString).mockResolvedValue('<svg><rect/></svg>');

    await refreshPreview(el);
    const html = templateToHtml(el.render());

    expect(html).toContain('qrcode-preview-ready');
    expect(vi.mocked(QRCode.toString)).toHaveBeenCalledOnce();
    expect(html).not.toContain('data:image/png');
  });

  it('renders png output in logo mode and overlays logo', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'value';
    el.nodeId = 'node-1';
    el.qrType = 'logo';
    el.logoSrc = 'https://cdn.example/logo.png';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('https://example.com');
    vi.mocked(QRCode.toCanvas).mockResolvedValue(undefined);

    installMockImage('load');

    await refreshPreview(el);
    const html = templateToHtml(el.render());

    expect(html).toContain('qrcode-preview-ready');
    expect(html).toContain('data:image/png;base64,fake');
    expect(vi.mocked(QRCode.toCanvas)).toHaveBeenCalledOnce();
    expect(canvas.toDataURL as ReturnType<typeof vi.fn>).toHaveBeenCalledWith('image/png');
    expect(ctx.drawImage).toHaveBeenCalled();
  });

  it('falls back gracefully when logo load fails', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'value';
    el.nodeId = 'node-1';
    el.qrType = 'logo';
    el.logoSrc = 'https://cdn.example/logo.png';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('https://example.com');
    vi.mocked(QRCode.toCanvas).mockResolvedValue(undefined);

    installMockImage('error');

    await refreshPreview(el);
    const html = templateToHtml(el.render());

    expect(html).toContain('qrcode-preview-ready');
    expect(html).toContain('data:image/png');
  });

  it('uses fillRect fallback when roundRect is unavailable', async () => {
    const el = new EpistolaQrCodePreview();
    const fallbackCtx: FakeCanvasContext = {
      ...ctx,
      roundRect: undefined,
      fillRect: vi.fn(),
      drawImage: vi.fn(),
    };
    const fallbackCanvas: FakeCanvas = {
      ...canvas,
      getContext: vi.fn(() => fallbackCtx),
    };

    (globalThis as { document?: unknown }).document = {
      createElement: vi.fn((tag: string) => (tag === 'canvas' ? fallbackCanvas : null)),
    };
    installMockImage('load');

    el.expression = 'value';
    el.nodeId = 'node-1';
    el.qrType = 'logo';
    el.logoSrc = 'https://cdn.example/logo.png';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('https://example.com');
    vi.mocked(QRCode.toCanvas).mockResolvedValue(undefined);

    await refreshPreview(el);

    expect(fallbackCtx.fillRect).toHaveBeenCalled();
    expect(fallbackCtx.drawImage).toHaveBeenCalled();
  });

  it('sets invalid state when qr generation throws', async () => {
    const el = new EpistolaQrCodePreview();
    el.expression = 'value';
    el.nodeId = 'node-1';
    el.qrType = 'standard';
    el.engine = createEngineMock() as never;

    vi.mocked(evaluateExpression).mockResolvedValue('https://example.com');
    vi.mocked(QRCode.toString).mockRejectedValue(new Error('fail'));

    await refreshPreview(el);

    expect(templateToHtml(el.render())).toContain(
      'Expression must resolve to text, number, or boolean',
    );
  });
});
