// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * @module @epistola/editor/data-form
 *
 * The interactive generation form's fields (contract ADR 0011), for the public form page. It
 * renders a form definition the server resolved, never a schema it picked itself: today a definition
 * generated from the data contract, rendered for the person filling it in (`data-form/respondent-form`);
 * later also a form an author configured. Validity is the server's: problems arrive as JSON Pointers.
 *
 * Public API:
 *   mountDataForm(options) → DataFormInstance
 */

import { render } from 'lit';
import {
  missingRequired,
  renderRespondentForm,
  setAt,
  type FieldProblem,
  type RespondentFormState,
} from './data-form/respondent-form.js';
import type { JsonObject, JsonSchema, JsonValue } from './data-contract/types.js';

/** What the server says the form is. One kind today. */
export type FormDefinition = { kind: 'generated'; schema: JsonSchema | null };

/** A field the server refused, by JSON Pointer; `kind` is `missing` for a required field left empty. */
export interface FormProblem {
  path: string;
  message: string;
  kind?: 'missing' | 'invalid';
}

export interface DataFormOptions {
  container: HTMLElement;
  definition: FormDefinition;
  data: JsonObject;
  problems: FormProblem[];
  /** The page is shown again after a refused submit: every problem shows at once. */
  submitted?: boolean;
  /** Called with the whole data object after every change. */
  onChange: (data: JsonObject) => void;
}

export interface DataFormInstance {
  data(): JsonObject;
  /** The server's problems from a preview or submit attempt; they all show. */
  showProblems(problems: FormProblem[]): void;
  /** Pointers of required fields still empty. */
  missing(): string[];
  /** Shows every problem, as after a submit attempt, and focuses the first field with one. */
  revealProblems(): void;
}

export function mountDataForm(options: DataFormOptions): DataFormInstance {
  if (options.definition.kind !== 'generated') {
    throw new Error(`Unknown form definition: ${options.definition.kind}`);
  }
  const state: RespondentFormState = {
    schema: options.definition.schema,
    data: options.data,
    problems: toProblems(options.problems),
    touched: new Set(),
    showAll: options.submitted === true,
  };

  const draw = () => {
    render(
      renderRespondentForm(state, {
        set(pointer: string, value: JsonValue | undefined) {
          state.data = setAt(state.data, pointer, value);
          // The change answers the field's own problem, a group's or list's above it (now no longer
          // empty), and anything below it (replaced or removed).
          for (const path of Array.from(state.problems.keys())) {
            if (
              path === pointer ||
              pointer.startsWith(`${path}/`) ||
              path.startsWith(`${pointer}/`)
            )
              state.problems.delete(path);
          }
          options.onChange(state.data);
          draw();
        },
        touch(pointer: string) {
          if (state.touched.has(pointer)) return;
          state.touched.add(pointer);
          draw();
        },
      }),
      options.container,
    );
  };
  draw();

  const focusFirst = () => {
    const first = options.container.querySelector<HTMLElement>(
      '[data-invalid] input, [data-invalid] select, [data-invalid] textarea, [data-invalid] button',
    );
    first?.focus();
    first?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  return {
    data: () => state.data,
    showProblems(problems) {
      state.problems = toProblems(problems);
      state.showAll = true;
      draw();
    },
    missing: () => missingRequired(state.schema, state.data),
    revealProblems() {
      state.showAll = true;
      draw();
      focusFirst();
    },
  };
}

function toProblems(problems: FormProblem[]): Map<string, FieldProblem> {
  return new Map(
    problems.map((p) => [p.path, { message: p.message, missing: p.kind === 'missing' }]),
  );
}
