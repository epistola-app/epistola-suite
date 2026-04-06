import { describe, it, expect } from 'vitest';
import { SYSTEM_PARAMETER_PATHS, SYSTEM_PARAM_MOCK_DATA } from './system-params.js';

describe('SYSTEM_PARAMETER_PATHS', () => {
  it('contains sys.pages.current', () => {
    const pagesCurrent = SYSTEM_PARAMETER_PATHS.find((fp) => fp.path === 'sys.pages.current');
    expect(pagesCurrent).toBeDefined();
    expect(pagesCurrent!.type).toBe('integer');
    expect(pagesCurrent!.system).toBe(true);
    expect(pagesCurrent!.description).toBeTruthy();
  });

  it('contains sys.pages.total', () => {
    const pagesTotal = SYSTEM_PARAMETER_PATHS.find((fp) => fp.path === 'sys.pages.total');
    expect(pagesTotal).toBeDefined();
    expect(pagesTotal!.type).toBe('integer');
    expect(pagesTotal!.system).toBe(true);
    expect(pagesTotal!.description).toBeTruthy();
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
  it('contains sys.pages.current mock value', () => {
    const sys = SYSTEM_PARAM_MOCK_DATA.sys as Record<string, unknown>;
    expect(sys).toBeDefined();
    const pages = sys.pages as Record<string, unknown>;
    expect(pages).toBeDefined();
    expect(pages.current).toBe(1);
  });

  it('contains sys.pages.total mock value', () => {
    const sys = SYSTEM_PARAM_MOCK_DATA.sys as Record<string, unknown>;
    const pages = sys.pages as Record<string, unknown>;
    expect(pages.total).toBe(1);
  });

  it('contains sys.render.time mock value', () => {
    const sys = SYSTEM_PARAM_MOCK_DATA.sys as Record<string, unknown>;
    expect(sys).toBeDefined();
    const render = sys.render as Record<string, unknown>;
    expect(render).toBeDefined();
    expect(render.time).toBe('2026-04-03T08:30:00Z');
  });
});
