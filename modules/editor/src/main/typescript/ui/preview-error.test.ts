// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { formatPreviewErrorResponse } from './preview-error.js';

describe('formatPreviewErrorResponse', () => {
  it('renders validation messages and codes without dumping JSON', () => {
    const response = JSON.stringify({
      errors: [
        {
          path: 'templateModel.nodes.stencil-1.props.parameterBindings.param1',
          message:
            "Stencil 'address-block' (component 'stencil-1') requires parameter 'param1', but it has no binding or default",
          code: 'NODE_PARAMETER_BINDING_MISSING_REQUIRED',
        },
      ],
    });

    expect(formatPreviewErrorResponse(response)).toBe(
      "Stencil 'address-block' (component 'stencil-1') requires parameter 'param1', but it has no binding or default [NODE_PARAMETER_BINDING_MISSING_REQUIRED]",
    );
  });

  it('renders multiple validation errors on separate lines', () => {
    const response = JSON.stringify({
      errors: [{ message: 'First error' }, { message: 'Second error', code: 'SECOND' }],
    });

    expect(formatPreviewErrorResponse(response)).toBe('First error\nSecond error [SECOND]');
  });

  it('uses problem detail and optional top-level code', () => {
    expect(
      formatPreviewErrorResponse(JSON.stringify({ detail: 'Preview is invalid', code: 'INVALID' })),
    ).toBe('Preview is invalid [INVALID]');
  });

  it('returns plain text responses and falls back for empty or unhelpful JSON', () => {
    expect(formatPreviewErrorResponse('Service unavailable')).toBe('Service unavailable');
    expect(formatPreviewErrorResponse('', 'Bad Request')).toBe('Bad Request');
    expect(formatPreviewErrorResponse('{}', 'Bad Request')).toBe('Bad Request');
  });
});
