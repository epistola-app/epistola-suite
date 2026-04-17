import { describe, expect, it } from 'vitest';
import { parseValidationWarning, shortId } from './ValidationMessages.js';

describe('parseValidationWarning', () => {
  it('parses request path with request id and json path', () => {
    const parsed = parseValidationWarning({
      path: 'request:req_1234567890abcdef $.user.email',
      message: 'must be string [status=FAILED correlation=corr-7]',
    });

    expect(parsed).toEqual({
      sourceLabel: 'Recent Request',
      requestId: 'req_1234567890abcdef',
      path: '$.user.email',
      message: 'must be string',
      status: 'FAILED',
      correlation: 'corr-7',
    });
  });

  it('parses request path with request id only', () => {
    const parsed = parseValidationWarning({
      path: 'request:req_1234567890abcdef',
      message: 'invalid payload',
    });

    expect(parsed).toEqual({
      sourceLabel: 'Recent Request',
      requestId: 'req_1234567890abcdef',
      path: '',
      message: 'invalid payload',
      status: undefined,
      correlation: undefined,
    });
  });

  it('parses schema warning and strips status metadata suffix', () => {
    const parsed = parseValidationWarning({
      path: '$.items[0].name',
      message: 'is required [status=IN_PROGRESS]',
    });

    expect(parsed).toEqual({
      sourceLabel: 'Schema',
      path: '$.items[0].name',
      message: 'is required',
      status: 'IN_PROGRESS',
      correlation: undefined,
    });
  });

  it('keeps message unchanged when there is no metadata suffix', () => {
    const parsed = parseValidationWarning({
      path: '$.count',
      message: 'must be integer',
    });

    expect(parsed).toEqual({
      sourceLabel: 'Schema',
      path: '$.count',
      message: 'must be integer',
      status: undefined,
      correlation: undefined,
    });
  });
});

describe('shortId', () => {
  it('keeps ids with length <= 12 unchanged', () => {
    expect(shortId('abc123')).toBe('abc123');
    expect(shortId('123456789012')).toBe('123456789012');
  });

  it('truncates long ids to first 8 and last 4 chars', () => {
    expect(shortId('req_1234567890abcdef')).toBe('req_1234…cdef');
  });
});
