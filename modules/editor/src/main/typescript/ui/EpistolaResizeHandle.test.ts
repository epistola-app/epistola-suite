import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_WIDTH,
  KEYBOARD_RESIZE_STEP,
  MAX_WIDTH,
  MIN_WIDTH,
  STORAGE_KEY,
  EpistolaResizeHandle,
} from './EpistolaResizeHandle.js';

const originalLocalStorage = globalThis.localStorage;

function setMockStorage(valueByKey: Record<string, string | null>): void {
  const storage: Storage = {
    getItem: (key) => {
      const value = valueByKey[key];
      return value ?? null;
    },
    setItem: () => {},
    removeItem: () => {},
    clear: () => {},
    key: () => null,
    length: 0,
  };

  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: storage,
  });
}

afterEach(() => {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: originalLocalStorage,
  });
});

describe('EpistolaResizeHandle storage and constants', () => {
  it('returns default width when no persisted value exists', () => {
    setMockStorage({});
    expect(EpistolaResizeHandle.getPersistedWidth()).toBe(DEFAULT_WIDTH);
  });

  it('clamps persisted width values to configured min/max range', () => {
    setMockStorage({
      [STORAGE_KEY]: String(MAX_WIDTH + 999),
    });
    expect(EpistolaResizeHandle.getPersistedWidth()).toBe(MAX_WIDTH);

    setMockStorage({
      [STORAGE_KEY]: String(MIN_WIDTH - 999),
    });
    expect(EpistolaResizeHandle.getPersistedWidth()).toBe(MIN_WIDTH);
  });

  it('falls back to default width when persisted value is not numeric', () => {
    setMockStorage({
      [STORAGE_KEY]: 'not-a-number',
    });
    expect(EpistolaResizeHandle.getPersistedWidth()).toBe(DEFAULT_WIDTH);
  });

  it('keeps keyboard resize step positive for width adjustments', () => {
    expect(KEYBOARD_RESIZE_STEP).toBeGreaterThan(0);
  });
});
