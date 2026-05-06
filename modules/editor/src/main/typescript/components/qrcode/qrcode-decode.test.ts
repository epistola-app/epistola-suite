import { describe, expect, it } from 'vitest';
import QRCode from 'qrcode';
import jsQR from 'jsqr';

const LOGO_SIZE_RATIO = 0.22;
const MODULE_SCALE = 4;
const QUIET_ZONE_MODULES = 4;

function bitMatrixToImageData(matrix: { size: number; get(row: number, col: number): number }): {
  data: Uint8ClampedArray;
  width: number;
  height: number;
} {
  const moduleCount = matrix.size;
  const totalModules = moduleCount + QUIET_ZONE_MODULES * 2;
  const pixelSize = totalModules * MODULE_SCALE;
  const data = new Uint8ClampedArray(pixelSize * pixelSize * 4);

  for (let i = 0; i < data.length; i += 4) {
    data[i] = 255;
    data[i + 1] = 255;
    data[i + 2] = 255;
    data[i + 3] = 255;
  }

  for (let moduleY = 0; moduleY < moduleCount; moduleY++) {
    for (let moduleX = 0; moduleX < moduleCount; moduleX++) {
      if (matrix.get(moduleX, moduleY) !== 1) continue;

      const startX = (moduleX + QUIET_ZONE_MODULES) * MODULE_SCALE;
      const startY = (moduleY + QUIET_ZONE_MODULES) * MODULE_SCALE;
      for (let dy = 0; dy < MODULE_SCALE; dy++) {
        for (let dx = 0; dx < MODULE_SCALE; dx++) {
          const idx = ((startY + dy) * pixelSize + startX + dx) * 4;
          data[idx] = 17;
          data[idx + 1] = 24;
          data[idx + 2] = 39;
          data[idx + 3] = 255;
        }
      }
    }
  }

  return { data, width: pixelSize, height: pixelSize };
}

function applyLogoHole(imageData: {
  data: Uint8ClampedArray;
  width: number;
  height: number;
}): void {
  const { data, width, height } = imageData;
  const logoSize = Math.max(16, Math.round(width * LOGO_SIZE_RATIO));
  const startX = Math.round((width - logoSize) / 2);
  const startY = Math.round((height - logoSize) / 2);

  for (let y = startY; y < startY + logoSize; y++) {
    for (let x = startX; x < startX + logoSize; x++) {
      const idx = (y * width + x) * 4;
      data[idx] = 255;
      data[idx + 1] = 255;
      data[idx + 2] = 255;
      data[idx + 3] = 255;
    }
  }
}

function generateAndDecode(value: string, withLogo: boolean): string | null {
  const qr = QRCode.create(value, {
    errorCorrectionLevel: withLogo ? 'H' : 'L',
  });
  const imageData = bitMatrixToImageData(qr.modules);

  if (withLogo) applyLogoHole(imageData);

  return jsQR(imageData.data, imageData.width, imageData.height)?.data ?? null;
}

describe('QR code editor round-trip decoding', () => {
  it.each<[string, string, boolean]>([
    ['standard short value', 'a'.repeat(10), false],
    ['standard medium value', 'b'.repeat(50), false],
    ['standard long value', 'c'.repeat(100), false],
    ['standard near max value', 'd'.repeat(2500), false],
    ['logo short value', 'a'.repeat(10), true],
    ['logo medium value', 'b'.repeat(50), true],
    ['logo long value', 'c'.repeat(100), true],
    ['logo near max value', 'd'.repeat(1200), true],
  ])('decodes %s', (_label, value, withLogo) => {
    expect(generateAndDecode(value, withLogo)).toBe(value);
  });
});
