// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * @module @epistola/editor/data-form
 *
 * The interactive generation form's fields (contract ADR 0011), for the public form page. It
 * renders a form definition the server resolved, never a schema it picked itself: today a definition
 * generated from the data contract, rendered with the data contract editor's example form; later
 * also a form an author configured. Validity is the server's: problems arrive as JSON Pointers.
 *
 * Public API:
 *   mountDataForm(options) → DataFormInstance
 */

// The styles are the data contract editor's: the page links /editor/data-contract-editor.css, so the
// shared stylesheet keeps its name for the data contract tab that links it too.
import { render } from 'lit';
import { renderExampleForm, setNestedValue } from './data-contract/sections/ExampleForm.js';
import type { JsonObject, JsonSchema, JsonValue } from './data-contract/types.js';

/** What the server says the form is. One kind today. */
export type FormDefinition = { kind: 'generated'; schema: JsonSchema | null };

/** A field the server refused, by JSON Pointer. */
export interface FormProblem {
  path: string;
  message: string;
}

export interface DataFormOptions {
  container: HTMLElement;
  definition: FormDefinition;
  data: JsonObject;
  problems: FormProblem[];
  /** Called with the whole data object after every change. */
  onChange: (data: JsonObject) => void;
}

export interface DataFormInstance {
  data(): JsonObject;
  showProblems(problems: FormProblem[]): void;
}

/** `/a/b/0` → `a.b.0`, the example form's path notation (RFC 6901 escapes undone). */
export function pointerToFormPath(pointer: string): string {
  return pointer
    .split('/')
    .slice(1)
    .map((token) => token.replace(/~1/g, '/').replace(/~0/g, '~'))
    .join('.');
}

export function mountDataForm(options: DataFormOptions): DataFormInstance {
  if (options.definition.kind !== 'generated') {
    throw new Error(`Unknown form definition: ${options.definition.kind}`);
  }
  const schema = options.definition.schema;
  let data = options.data;
  let errors = toErrors(options.problems);

  const draw = () => {
    render(
      renderExampleForm(
        schema,
        data,
        (path: string, value: JsonValue | undefined) => {
          data = setNestedValue(data, path, value);
          errors.delete(path);
          options.onChange(data);
          draw();
        },
        errors,
      ),
      options.container,
    );
  };
  draw();

  return {
    data: () => data,
    showProblems(problems) {
      errors = toErrors(problems);
      draw();
    },
  };
}

function toErrors(problems: FormProblem[]): Map<string, string> {
  return new Map(problems.map((p) => [pointerToFormPath(p.path), p.message]));
}
