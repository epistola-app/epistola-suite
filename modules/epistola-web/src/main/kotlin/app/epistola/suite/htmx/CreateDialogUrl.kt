package app.epistola.suite.htmx

import java.net.URI

/**
 * Builds the URL that represents "the list, with the create dialog open" by
 * appending a valueless `create` query parameter to the browser's current URL.
 *
 * Opening a create dialog is the *list view with a modifier*, not a separate
 * resource — so the URL is the list URL plus `create`. The merge is done on the
 * server (driven by the `HX-Current-URL` request header) precisely so it stays a
 * single, testable function instead of scattered query-string wrangling in inline
 * JS: every existing parameter is preserved and only `create` is added. That is
 * what makes it safe as more params appear over time (e.g. a list's own `catalog`
 * filter dropdown) — this never clobbers what is already there.
 *
 * Entity-agnostic: any create-dialog list handler that opts into URL-addressable
 * dialogs calls this from its `newForm` to set the `HX-Push-Url`.
 *
 * The result is a path-relative URL (path + query, no scheme/host), which is what
 * `HX-Push-Url` wants and keeps history entries origin-independent.
 *
 * @param currentUrl the value of the `HX-Current-URL` header, or null/blank if absent
 * @param fallbackPath the list path to use when no current URL is available
 */
fun urlWithCreateParam(currentUrl: String?, fallbackPath: String): String {
    val source = currentUrl?.takeIf { it.isNotBlank() }
        ?: return "$fallbackPath?create"

    val uri = URI(source)
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: fallbackPath
    val existing = uri.rawQuery

    val alreadyHasCreate = existing != null &&
        existing.split('&').any { it == "create" || it.startsWith("create=") }

    val query = when {
        existing.isNullOrEmpty() -> "create"
        alreadyHasCreate -> existing
        else -> "$existing&create"
    }
    return "$path?$query"
}
