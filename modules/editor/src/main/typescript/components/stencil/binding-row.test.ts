import { describe, expect, it } from 'vitest';
import { parseBindingSaveError } from './binding-row.js';

describe('parseBindingSaveError', () => {
  it('extracts parameter name and parser message from binding syntax errors', () => {
    expect(
      parseBindingSaveError(
        "NODE_PARAMETER_BINDING_SYNTAX_INVALID: parameter binding 'param1' expression is invalid — Expected expression after operator",
      ),
    ).toEqual({
      paramName: 'param1',
      message: 'Expected expression after operator',
    });
  });

  it('uses a fallback message when the parser message is absent', () => {
    expect(
      parseBindingSaveError(
        "NODE_PARAMETER_BINDING_SYNTAX_INVALID: parameter binding 'param1' expression is invalid",
      ),
    ).toEqual({
      paramName: 'param1',
      message: 'Invalid JSONata expression',
    });
  });

  it('ignores unrelated save errors', () => {
    expect(parseBindingSaveError('Failed to save draft')).toBeNull();
  });
});
