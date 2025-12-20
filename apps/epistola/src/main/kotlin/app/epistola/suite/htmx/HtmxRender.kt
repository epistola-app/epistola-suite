package app.epistola.suite.htmx

import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI

/**
 * Renders a Thymeleaf template with HTMX-aware response handling.
 *
 * For HTMX requests: renders the specified fragment (if provided).
 * For regular requests: either redirects (if redirectOnSuccess is set) or renders the full template.
 *
 * @param template The Thymeleaf template name (e.g., "templates/list")
 * @param fragment The fragment to render for HTMX requests (e.g., "rows" renders "templates/list :: rows")
 * @param model The model attributes to pass to the template
 * @param redirectOnSuccess URL to redirect to for non-HTMX requests (typically after form submission)
 * @return A ServerResponse with the appropriate content or redirect
 */
fun ServerRequest.render(
    template: String,
    fragment: String? = null,
    model: Map<String, Any> = emptyMap(),
    redirectOnSuccess: String? = null,
): ServerResponse = when {
    isHtmx && fragment != null -> {
        ServerResponse.ok().render("$template :: $fragment", model)
    }
    !isHtmx && redirectOnSuccess != null -> {
        ServerResponse.seeOther(URI.create(redirectOnSuccess)).build()
    }
    else -> {
        ServerResponse.ok().render(template, model)
    }
}

/**
 * Renders a Thymeleaf template, always returning the rendered content (no redirect).
 *
 * For HTMX requests with a fragment: renders only the fragment.
 * Otherwise: renders the full template.
 *
 * @param template The Thymeleaf template name
 * @param fragment The fragment to render for HTMX requests
 * @param model The model attributes to pass to the template
 * @return A ServerResponse with the rendered content
 */
fun ServerRequest.renderTemplate(
    template: String,
    fragment: String? = null,
    model: Map<String, Any> = emptyMap(),
): ServerResponse {
    val templatePath = if (isHtmx && fragment != null) {
        "$template :: $fragment"
    } else {
        template
    }
    return ServerResponse.ok().render(templatePath, model)
}

/**
 * Creates an HTMX-aware response using a Kotlin DSL.
 *
 * Supports:
 * - Single and multiple fragments (Out-of-Band swaps)
 * - Conditional rendering logic
 * - Response headers (trigger, pushUrl, reswap, retarget)
 * - Non-HTMX fallback handling
 *
 * Example usage:
 * ```kotlin
 * return request.htmx {
 *     fragment("templates/list", "rows") {
 *         "templates" to templates
 *     }
 *     oob("components/toast", "success") {
 *         "message" to "Saved!"
 *     }
 *     trigger("itemSaved")
 *     onNonHtmx { redirect("/templates") }
 * }
 * ```
 *
 * @param block The DSL builder block
 * @return A ServerResponse appropriate for the request type
 */
fun ServerRequest.htmx(block: HtmxResponseBuilder.() -> Unit): ServerResponse = HtmxResponseBuilder(this).apply(block).build()
