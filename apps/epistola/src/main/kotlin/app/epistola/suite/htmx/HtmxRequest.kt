package app.epistola.suite.htmx

import org.springframework.web.servlet.function.ServerRequest

/**
 * Extension properties for detecting and reading HTMX request headers.
 *
 * @see <a href="https://htmx.org/reference/#request_headers">HTMX Request Headers</a>
 */

/** True if this request was made by HTMX (contains HX-Request header). */
val ServerRequest.isHtmx: Boolean
    get() = headers().firstHeader("HX-Request") == "true"

/** The ID of the element that triggered the request. */
val ServerRequest.htmxTrigger: String?
    get() = headers().firstHeader("HX-Trigger")

/** The name of the element that triggered the request. */
val ServerRequest.htmxTriggerName: String?
    get() = headers().firstHeader("HX-Trigger-Name")

/** The ID of the target element. */
val ServerRequest.htmxTarget: String?
    get() = headers().firstHeader("HX-Target")

/** The current URL of the browser. */
val ServerRequest.htmxCurrentUrl: String?
    get() = headers().firstHeader("HX-Current-URL")

/** True if the request is for history restoration after a miss in the local history cache. */
val ServerRequest.htmxHistoryRestoreRequest: Boolean
    get() = headers().firstHeader("HX-History-Restore-Request") == "true"

/** The user response to an hx-prompt. */
val ServerRequest.htmxPrompt: String?
    get() = headers().firstHeader("HX-Prompt")

/** True if the request is via an element using hx-boost. */
val ServerRequest.htmxBoosted: Boolean
    get() = headers().firstHeader("HX-Boosted") == "true"
