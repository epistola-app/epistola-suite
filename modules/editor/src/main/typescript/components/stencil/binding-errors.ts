/**
 * Maps a structured save error from the backend to a per-parameter binding
 * error the stencil UI can render inline.
 *
 * The backend returns an RFC 9457 problem `type` URI (the machine-readable
 * discriminator) and a field path (`errors[0].field` → carried as `field`)
 * instead of encoding both inside a free-text message. This module reads those
 * structured fields — no regex over human text — so a reworded backend message
 * never breaks the editor.
 */
import type { SaveState } from '../../ui/save-service.js';

/**
 * Component-state key for the per-parameter binding errors the editor hands to
 * the stencil inspector. Exported so producer (EpistolaEditor) and consumer
 * (StencilInspector) share one symbol instead of duplicating a string literal.
 */
export const STENCIL_BINDING_ERRORS_KEY = 'stencil:binding-errors';

/** Per-parameter inline error map keyed by parameter name. */
export type BindingErrors = Record<string, string>;

/** Problem `type` URI for the JSONata binding syntax error (see backend ValidationCode). */
export const BINDING_SYNTAX_INVALID_TYPE = 'https://epistola.app/errors/node-parameter-binding-syntax-invalid';

export interface SaveErrorInfo {
  /** Machine-readable problem `type` URI, when the save failed validation. */
  type?: string;
  /** Field path the error points at (e.g. content.stencil.props.parameterBindings.param1). */
  field?: string;
  /** Human-readable message (display only). */
  message: string;
}

export interface BindingSaveError {
  /** The parameter whose binding is invalid. */
  paramName: string;
  /** Human-readable detail to show on the row. */
  message: string;
}

/**
 * Returns `{ paramName, message }` when the save error is a binding syntax
 * error, `null` otherwise. Param name comes from the structured `field` path;
 * the display detail is the parser text after the ` — ` separator (the message
 * is display-only and may be reworded freely without affecting this code).
 */
export function parseBindingSaveError(error: SaveErrorInfo): BindingSaveError | null {
  if (error.type !== BINDING_SYNTAX_INVALID_TYPE) return null;

  const field = error.field ?? '';
  const paramName = field.includes('.') ? field.slice(field.lastIndexOf('.') + 1) : field;
  if (!paramName) return null;

  const message =
    error.message && error.message.includes(' — ')
      ? error.message.slice(error.message.indexOf(' — ') + 3)
      : 'Invalid JSONata expression';

  return { paramName, message };
}

/**
 * Derives the next `stencil:binding-errors` component-state value from a save
 * state. Pure so the editor↔inspector channel logic is unit-testable without
 * the editor/engine:
 *  - `null`      → a successful save: clear any stored errors
 *  - `{...}`     → a binding syntax error: show it on that parameter's row
 *  - `undefined` → leave existing state untouched (non-binding error, or a
 *                  non-terminal status like saving/dirty)
 */
export function bindingErrorsForSaveState(state: SaveState): BindingErrors | null | undefined {
  if (state.status === 'saved') return null;
  if (state.status !== 'error') return undefined;
  const parsed = parseBindingSaveError(state);
  return parsed ? { [parsed.paramName]: parsed.message } : undefined;
}
