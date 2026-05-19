/**
 * Maps a structured save error from the backend to a per-parameter binding
 * error the stencil UI can render inline.
 *
 * The backend now returns a first-class machine code (`code`) and a field path
 * (`errors[0].field` → carried as `field`) instead of encoding both inside a
 * free-text message. This module reads those structured fields — no regex over
 * human text — so a reworded backend message never breaks the editor.
 */

/** Wire value of the JSONata binding syntax error code (see backend ValidationCode). */
export const NODE_PARAMETER_BINDING_SYNTAX_INVALID = 'NODE_PARAMETER_BINDING_SYNTAX_INVALID';

export interface SaveErrorInfo {
  /** Machine-readable validation code, when the save failed validation. */
  code?: string;
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
  if (error.code !== NODE_PARAMETER_BINDING_SYNTAX_INVALID) return null;

  const field = error.field ?? '';
  const paramName = field.includes('.') ? field.slice(field.lastIndexOf('.') + 1) : field;
  if (!paramName) return null;

  const message =
    error.message && error.message.includes(' — ')
      ? error.message.slice(error.message.indexOf(' — ') + 3)
      : 'Invalid JSONata expression';

  return { paramName, message };
}
