import { describe, expect, it } from 'vitest';
import QRCode from 'qrcode';
import jsQR from 'jsqr';

const LOGO_SIZE_RATIO = 0.22;
const MODULE_SCALE = 4;
const QUIET_ZONE_MODULES = 4;

/**
 * Renders a ZXing BitMatrix to a Uint8ClampedArray (RGBA) that jsqr can decode.
 */
function bitMatrixToImageData(matrix: { size: number; get(row: number, col: number): number }): {
  data: Uint8ClampedArray;
  width: number;
  height: number;
} {
  const moduleCount = matrix.size;
  const totalModules = moduleCount + QUIET_ZONE_MODULES * 2;
  const pixelSize = totalModules * MODULE_SCALE;
  const data = new Uint8ClampedArray(pixelSize * pixelSize * 4);

  // Fill white
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 255; // R
    data[i + 1] = 255; // G
    data[i + 2] = 255; // B
    data[i + 3] = 255; // A
  }

  // Draw modules
  for (let moduleY = 0; moduleY < moduleCount; moduleY++) {
    for (let moduleX = 0; moduleX < moduleCount; moduleX++) {
      if (matrix.get(moduleX, moduleY) === 1) {
        const startX = (moduleX + QUIET_ZONE_MODULES) * MODULE_SCALE;
        const startY = (moduleY + QUIET_ZONE_MODULES) * MODULE_SCALE;
        for (let dy = 0; dy < MODULE_SCALE; dy++) {
          for (let dx = 0; dx < MODULE_SCALE; dx++) {
            const px = startX + dx;
            const py = startY + dy;
            const idx = (py * pixelSize + px) * 4;
            data[idx] = 17; // R (~#111827)
            data[idx + 1] = 24; // G
            data[idx + 2] = 39; // B
            data[idx + 3] = 255; // A
          }
        }
      }
    }
  }

  return { data, width: pixelSize, height: pixelSize };
}

/**
 * Simulates a logo overlay by clearing a white square in the center.
 */
function applyLogoHole(imageData: { data: Uint8ClampedArray; width: number; height: number }): void {
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

/**
 * Generates a QR code for the given value and decodes it with jsqr.
 */
function generateAndDecode(value: string, withLogo: boolean): string | null {
  const qr = QRCode.create(value, {
    errorCorrectionLevel: withLogo ? 'H' : 'L',
  });

  const imageData = bitMatrixToImageData(qr.modules);

  if (withLogo) {
    applyLogoHole(imageData);
  }

  const result = jsQR(imageData.data, imageData.width, imageData.height);
  return result?.data ?? null;
}

describe('QR code round-trip decoding', () => {
  const testCases = [
    { label: 'short (10 chars)', value: 'a'.repeat(10) },
    { label: 'medium (50 chars)', value: 'b'.repeat(50) },
    { label: 'long (100 chars)', value: 'c'.repeat(100) },
    { label: 'near-max standard (2500 chars)', value: 'd'.repeat(2500) },
  ];

  // Level H has lower capacity; max byte payload is ~1273 chars.
  const logoTestCases = [
    { label: 'short (10 chars)', value: 'a'.repeat(10) },
    { label: 'medium (50 chars)', value: 'b'.repeat(50) },
    { label: 'long (100 chars)', value: 'c'.repeat(100) },
    { label: 'near-max logo (1200 chars)', value: 'd'.repeat(1200) },
  ];

  for (const { label, value } of testCases) {
    it(`standard mode decodes ${label}`, () => {
      const decoded = generateAndDecode(value, false);
      expect(decoded).toBe(value);
    });
  }

  for (const { label, value } of logoTestCases) {
    it(`logo mode decodes ${label}`, () => {
      const decoded = generateAndDecode(value, true);
      expect(decoded).toBe(value);
    });
  }
});
