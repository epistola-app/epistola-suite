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

/**
 * Extract a path variable and parse it with a validator function.
 * Returns null if parsing fails.
 *
 * Usage:
 * ```kotlin
 * val themeId = request.pathId("themeId") { ThemeId.validateOrNull(it) }
 *     ?: return ServerResponse.badRequest().build()
 * ```
 */
fun <T> ServerRequest.pathId(name: String, parse: (String) -> T?): T? =
    parse(pathVariable(name))

/**
 * Get a query parameter with optional default value.
 *
 * Usage:
 * ```kotlin
 * val searchTerm = request.queryParam("q")  // returns null if not present
 * val category = request.queryParam("category", "all")  // returns "all" if not present
 * ```
 */
fun ServerRequest.queryParam(name: String): String? =
    param(name).orElse(null)

fun ServerRequest.queryParam(name: String, default: String): String =
    param(name).orElse(default)

/**
 * Get a query parameter as an integer with optional default value.
 *
 * Usage:
 * ```kotlin
 * val offset = request.queryParamInt("offset", 0)
 * val limit = request.queryParamInt("limit", 100)
 * ```
 */
fun ServerRequest.queryParamInt(name: String, default: Int): Int =
    param(name).orElse(null)?.toIntOrNull() ?: default
