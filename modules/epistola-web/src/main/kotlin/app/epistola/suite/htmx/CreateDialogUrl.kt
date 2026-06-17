package app.epistola.suite.htmx

import java.net.URI

/**
 * Builds the URL that represents "the list, with a dialog open" by appending a
 * valueless dialog query parameter (e.g. `create`, or `upload` for the file-upload
 * forms) to the browser's current URL.
 *
 * Opening a list's dialog is the *list view with a modifier*, not a separate
 * resource — so the URL is the list URL plus the param. The merge is done on the
 * server (driven by the `HX-Current-URL` request header) precisely so it stays a
 * single, testable function instead of scattered query-string wrangling in inline
 * JS: every existing parameter is preserved and only [param] is added. That is
 * what makes it safe as more params appear over time (e.g. a list's own `catalog`
 * filter dropdown) — this never clobbers what is already there.
 *
 * Entity-agnostic: any list handler that opts into a URL-addressable dialog calls
 * this from its `newForm` to set the `HX-Push-Url`; the param name must match the
 * dialog's `data-dialog-param` (the client reconcile reads the same name).
 *
 * The result is a path-relative URL (path + query, no scheme/host), which is what
 * `HX-Push-Url` wants and keeps history entries origin-independent.
 *
 * @param currentUrl the value of the `HX-Current-URL` header, or null/blank if absent
 * @param fallbackPath the list path to use when no current URL is available
 * @param param the dialog-open query parameter to add (e.g. `create`, `upload`)
 */
fun urlWithDialogParam(currentUrl: String?, fallbackPath: String, param: String): String {
    val source = currentUrl?.takeIf { it.isNotBlank() }
        ?: return "$fallbackPath?$param"

    val uri = URI(source)
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: fallbackPath
    val existing = uri.rawQuery

    val alreadyHasParam = existing != null &&
        existing.split('&').any { it == param || it.startsWith("$param=") }

    val query = when {
        existing.isNullOrEmpty() -> param
        alreadyHasParam -> existing
        else -> "$existing&$param"
    }
    return "$path?$query"
}

/**
 * Convenience for the common create dialog: [urlWithDialogParam] with `create`.
 */
fun urlWithCreateParam(currentUrl: String?, fallbackPath: String): String = urlWithDialogParam(currentUrl, fallbackPath, "create")
