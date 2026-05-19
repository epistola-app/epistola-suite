import { describe, expect, it } from 'vitest';
import { parseBindingSaveError } from './binding-errors.js';

describe('parseBindingSaveError', () => {
  it('extracts parameter name from the structured field and parser message from the detail', () => {
    expect(
      parseBindingSaveError({
        code: 'NODE_PARAMETER_BINDING_SYNTAX_INVALID',
        field: 'content.stencil.props.parameterBindings.param1',
        message:
          "parameter binding 'param1' expression is invalid — Expected expression after operator",
      }),
    ).toEqual({
      paramName: 'param1',
      message: 'Expected expression after operator',
    });
  });

  it('uses a fallback message when the message has no detail separator', () => {
    expect(
      parseBindingSaveError({
        code: 'NODE_PARAMETER_BINDING_SYNTAX_INVALID',
        field: 'content.stencil.props.parameterBindings.param1',
        message: "parameter binding 'param1' expression is invalid",
      }),
    ).toEqual({
      paramName: 'param1',
      message: 'Invalid JSONata expression',
    });
  });

  it('ignores save errors with a different code', () => {
    expect(
      parseBindingSaveError({
        code: 'NODE_PARAMETER_BINDING_MISSING_REQUIRED',
        field: 'content.stencil.props.parameterBindings.param1',
        message: 'required parameter has no binding',
      }),
    ).toBeNull();
  });

  it('ignores generic save errors with no code', () => {
    expect(parseBindingSaveError({ message: 'Failed to save draft' })).toBeNull();
  });

  it('returns null when the code matches but no field is present', () => {
    expect(
      parseBindingSaveError({
        code: 'NODE_PARAMETER_BINDING_SYNTAX_INVALID',
        message: "parameter binding 'param1' expression is invalid — bad",
      }),
    ).toBeNull();
  });

  it('does not regex-parse the message — a reworded message still resolves the param', () => {
    expect(
      parseBindingSaveError({
        code: 'NODE_PARAMETER_BINDING_SYNTAX_INVALID',
        field: 'content.stencil.props.parameterBindings.greeting',
        message: 'totally reworded backend text — the real detail',
      }),
    ).toEqual({
      paramName: 'greeting',
      message: 'the real detail',
    });
  });
});
