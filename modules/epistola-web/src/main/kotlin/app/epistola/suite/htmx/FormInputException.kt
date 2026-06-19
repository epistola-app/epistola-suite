package app.epistola.suite.htmx

/**
 * A general, non-field-specific form error — something the user must fix, but that
 * isn't tied to a single input: a malformed JSON blob, a cross-field rule, an
 * operational rejection discovered during submission.
 *
 * Handlers **throw** this (rather than hand-rendering an error region); the MVC error
 * handler `UiHandlerExceptionResolver` renders it — as an out-of-band swap into the
 * dialog's general error region for an HTMX request, or as `problem+json` for a data
 * caller (e.g. the editor). (`UiExceptionFilter` is only the last-resort net for
 * exceptions that escape the dispatch.)
 *
 * Field-specific errors stay as **data** in the `errors` map (see [FormBinder]) and are
 * rendered by the handler next to their input — do not use this exception for those.
 */
class FormInputException(message: String) : RuntimeException(message)
