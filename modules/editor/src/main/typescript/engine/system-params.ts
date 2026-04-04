/**
 * System parameter definitions for the template editor.
 *
 * System parameters are runtime values provided by the rendering engine
 * (e.g., page numbers) that are independent of the template's data model.
 * They live under the `sys.*` namespace.
 */

import type { FieldPath } from './schema-paths.js';

/**
 * All system parameters available in the rendering engine.
 * These are appended to schema-derived field paths in the editor.
 */
export const SYSTEM_PARAMETER_PATHS: FieldPath[] = [
  {
    path: 'sys.page.number',
    type: 'integer',
    system: true,
    description: 'Current page number. Available in page headers/footers only.',
  },
  {
    path: 'sys.page.total',
    type: 'integer',
    system: true,
    description: 'Total number of pages. Available in page headers/footers only.',
  },
];

/**
 * Mock values for system parameters, used in expression preview
 * within the editor. Structured to match the nested `sys.*` namespace.
 */

const TOTAL_PAGES = 1;

export const SYSTEM_PARAM_MOCK_DATA: Record<string, unknown> = {
  sys: {
    page: {
      number: 1,
      total: TOTAL_PAGES,
    },
  },
};
