import { describe, expect, it } from 'vitest';
import { bindingErrorsForSaveState, parseBindingSaveError } from './binding-errors.js';
import type { SaveState } from '../../ui/save-service.js';

describe('parseBindingSaveError', () => {
  it('extracts parameter name from the structured field and parser message from the detail', () => {
    expect(
      parseBindingSaveError({
        type: 'https://epistola.app/errors/node-parameter-binding-syntax-invalid',
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
        type: 'https://epistola.app/errors/node-parameter-binding-syntax-invalid',
        field: 'content.stencil.props.parameterBindings.param1',
        message: "parameter binding 'param1' expression is invalid",
      }),
    ).toEqual({
      paramName: 'param1',
      message: 'Invalid JSONata expression',
    });
  });

  it('ignores save errors with a different type', () => {
    expect(
      parseBindingSaveError({
        type: 'https://epistola.app/errors/node-parameter-binding-missing-required',
        field: 'content.stencil.props.parameterBindings.param1',
        message: 'required parameter has no binding',
      }),
    ).toBeNull();
  });

  it('ignores generic save errors with no type', () => {
    expect(parseBindingSaveError({ message: 'Failed to save draft' })).toBeNull();
  });

  it('returns null when the type matches but no field is present', () => {
    expect(
      parseBindingSaveError({
        type: 'https://epistola.app/errors/node-parameter-binding-syntax-invalid',
        message: "parameter binding 'param1' expression is invalid — bad",
      }),
    ).toBeNull();
  });

  it('does not regex-parse the message — a reworded message still resolves the param', () => {
    expect(
      parseBindingSaveError({
        type: 'https://epistola.app/errors/node-parameter-binding-syntax-invalid',
        field: 'content.stencil.props.parameterBindings.greeting',
        message: 'totally reworded backend text — the real detail',
      }),
    ).toEqual({
      paramName: 'greeting',
      message: 'the real detail',
    });
  });
});

describe('bindingErrorsForSaveState (editor↔inspector channel)', () => {
  it('clears stored errors on a successful save', () => {
    expect(bindingErrorsForSaveState({ status: 'saved' })).toBeNull();
  });

  it('maps a binding syntax error to a per-parameter record', () => {
    const state: SaveState = {
      status: 'error',
      type: 'https://epistola.app/errors/node-parameter-binding-syntax-invalid',
      field: 'content.stencil.props.parameterBindings.param1',
      message: "parameter binding 'param1' expression is invalid — bad token",
    };
    expect(bindingErrorsForSaveState(state)).toEqual({ param1: 'bad token' });
  });

  it('leaves state untouched for a non-binding error', () => {
    const state: SaveState = { status: 'error', message: 'Network error' };
    expect(bindingErrorsForSaveState(state)).toBeUndefined();
  });

  it('leaves state untouched for non-terminal statuses', () => {
    expect(bindingErrorsForSaveState({ status: 'saving' })).toBeUndefined();
    expect(bindingErrorsForSaveState({ status: 'dirty' })).toBeUndefined();
    expect(bindingErrorsForSaveState({ status: 'idle' })).toBeUndefined();
  });
});
