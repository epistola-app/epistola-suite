package app.epistola.suite.htmx.table

import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/** The page-size choices every data-table list offers; one place so they change together. */
val PAGE_SIZES = listOf(10, 25, 50)

/**
 * The standard data-table list response, shared by every list handler so the htmx wiring lives
 * in one place: on an htmx request render the page's `data-table-fragment` into
 * `#data-table-container` and push [canonicalUrl] (so the bookmarkable URL tracks the view); on a
 * full-page load render the whole page with [pageTitle] added. [model] supplies the data-table
 * model keys (`columns`/`query`/`paged`/`pageSizeOptions` plus any page-specific filter state).
 * See ADR 0007.
 */
fun ServerRequest.dataTableResponse(
    view: String,
    pageTitle: String,
    canonicalUrl: String,
    model: ModelBuilder.() -> Unit,
): ServerResponse = htmx {
    fragment(view, "data-table-fragment", model)
    pushUrl(canonicalUrl)
    onNonHtmx {
        page(view) {
            model(this)
            "pageTitle" to pageTitle
        }
    }
}
