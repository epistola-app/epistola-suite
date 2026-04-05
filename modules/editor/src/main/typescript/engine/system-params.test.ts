import { describe, it, expect } from 'vitest';
import { SYSTEM_PARAMETER_PATHS, SYSTEM_PARAM_MOCK_DATA } from './system-params.js';

describe('SYSTEM_PARAMETER_PATHS', () => {
  it('contains sys.page.number', () => {
    const pageNumber = SYSTEM_PARAMETER_PATHS.find((fp) => fp.path === 'sys.page.number');
    expect(pageNumber).toBeDefined();
    expect(pageNumber!.type).toBe('integer');
    expect(pageNumber!.system).toBe(true);
    expect(pageNumber!.description).toBeTruthy();
  });

  it('contains sys.render.time', () => {
    const renderTime = SYSTEM_PARAMETER_PATHS.find((fp) => fp.path === 'sys.render.time');
    expect(renderTime).toBeDefined();
    expect(renderTime!.type).toBe('datetime');
    expect(renderTime!.system).toBe(true);
    expect(renderTime!.description).toBeTruthy();
  });

  it('all entries are marked as system parameters', () => {
    for (const fp of SYSTEM_PARAMETER_PATHS) {
      expect(fp.system).toBe(true);
    }
  });

  it('all entries have descriptions', () => {
    for (const fp of SYSTEM_PARAMETER_PATHS) {
      expect(fp.description).toBeTruthy();
    }
  });
});

describe('SYSTEM_PARAM_MOCK_DATA', () => {
  it('contains sys.page.number mock value', () => {
    const sys = SYSTEM_PARAM_MOCK_DATA.sys as Record<string, unknown>;
    expect(sys).toBeDefined();
    const page = sys.page as Record<string, unknown>;
    expect(page).toBeDefined();
    expect(page.number).toBe(1);
  });

  it('contains sys.render.time mock value', () => {
    const sys = SYSTEM_PARAM_MOCK_DATA.sys as Record<string, unknown>;
    expect(sys).toBeDefined();
    const render = sys.render as Record<string, unknown>;
    expect(render).toBeDefined();
    expect(render.time).toBe('2026-04-03T08:30:00Z');
  });
});
