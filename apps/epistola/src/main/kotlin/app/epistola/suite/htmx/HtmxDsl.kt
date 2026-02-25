package app.epistola.suite.htmx

import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI

/**
 * Marker annotation for HTMX DSL scope control.
 * Prevents accidental nesting of DSL builders.
 */
@DslMarker
annotation class HtmxDsl

/**
 * A fragment to be rendered in the HTMX response.
 *
 * @property template The Thymeleaf template path
 * @property fragmentName The fragment name within the template (null for full template)
 * @property model The model attributes for this fragment
 * @property isOob Whether this is an Out-of-Band swap fragment
 */
data class HtmxFragment(
    val template: String,
    val fragmentName: String?,
    val model: Map<String, Any>,
    val isOob: Boolean = false,
)

/**
 * Builder for constructing model maps in a DSL-friendly way.
 */
@HtmxDsl
class ModelBuilder {
    private val model = mutableMapOf<String, Any>()

    /**
     * Adds a key-value pair to the model.
     * Usage: `"key" to value`
     */
    infix fun String.to(value: Any) {
        model[this] = value
    }

    internal fun build(): Map<String, Any> = model.toMap()
}

/**
 * Builder for non-HTMX request handling within the htmx DSL.
 * Allows both page rendering and redirects.
 *
 * Usage:
 * ```kotlin
 * onNonHtmx {
 *     page("templates/list") {
 *         "templates" to templates
 *     }
 * }
 * ```
 */
@HtmxDsl
class NonHtmxBuilder {
    private var response: ServerResponse? = null

    /**
     * Render a full page (wrapped in layout/shell).
     * Only the last page() or redirect() call is used.
     */
    fun page(
        contentView: String,
        model: ModelBuilder.() -> Unit = {},
    ) {
        val modelMap = ModelBuilder().apply(model).build()
        val pageModel = (modelMap + mapOf("contentView" to contentView)).toMutableMap()
        response = ServerResponse.ok().render("layout/shell", pageModel)
    }

    /**
     * Render a full page with custom status code.
     */
    fun page(
        status: Int,
        contentView: String,
        model: ModelBuilder.() -> Unit = {},
    ) {
        val modelMap = ModelBuilder().apply(model).build()
        val pageModel = (modelMap + mapOf("contentView" to contentView)).toMutableMap()
        response = ServerResponse.status(status).render("layout/shell", pageModel)
    }

    /**
     * Redirect to a URL.
     * Only the last page() or redirect() call is used.
     */
    fun redirect(url: String) {
        response = ServerResponse.seeOther(URI.create(url)).build()
    }

    internal fun build(): ServerResponse? = response
}

/**
 * Main builder for constructing HTMX responses.
 *
 * Supports:
 * - Primary fragments and Out-of-Band (OOB) fragments
 * - HTMX response headers (trigger, pushUrl, reswap, retarget)
 * - Non-HTMX request fallback handling
 *
 * @property request The incoming ServerRequest (used to detect HTMX requests)
 */
@HtmxDsl
class HtmxResponseBuilder(private val request: ServerRequest) {
    private val fragments = mutableListOf<HtmxFragment>()
    private val headers = mutableMapOf<String, String>()
    private var nonHtmxHandler: (() -> ServerResponse)? = null
    private var fullTemplate: String? = null

    /**
     * Adds a primary fragment to the response.
     * This is the main content that replaces the hx-target element.
     *
     * @param template The Thymeleaf template path
     * @param fragmentName Optional fragment name (e.g., "rows" renders "template :: rows")
     * @param model Lambda to build the model for this fragment
     */
    fun fragment(
        template: String,
        fragmentName: String? = null,
        model: ModelBuilder.() -> Unit = {},
    ) {
        val modelMap = ModelBuilder().apply(model).build()
        fragments.add(HtmxFragment(template, fragmentName, modelMap, isOob = false))
        if (fullTemplate == null) {
            fullTemplate = template
        }
    }

    /**
     * Adds an Out-of-Band (OOB) fragment to the response.
     * OOB fragments update other parts of the page outside the main hx-target.
     *
     * The fragment must have an id attribute matching an element on the page.
     *
     * @param template The Thymeleaf template path
     * @param fragmentName Optional fragment name
     * @param model Lambda to build the model for this fragment
     */
    fun oob(
        template: String,
        fragmentName: String? = null,
        model: ModelBuilder.() -> Unit = {},
    ) {
        val modelMap = ModelBuilder().apply(model).build()
        fragments.add(HtmxFragment(template, fragmentName, modelMap, isOob = true))
    }

    /**
     * Renders a form error fragment with HTMX OOB swap.
     * Convenience helper for inline form error responses.
     *
     * Automatically:
     * - Spreads formData and errors into the model
     * - Sets HxSwap to OUTER_HTML (replaces the entire form element)
     * - Can be further customized with retarget(), onNonHtmx(), etc.
     *
     * Usage:
     * ```kotlin
     * return request.htmx {
     *     formError("tenants/list", "create-form", result)
     *     onNonHtmx { redirect("/tenants") }
     * }
     * ```
     *
     * @param template The Thymeleaf template path (e.g., "tenants/list")
     * @param fragmentName The fragment name to render (e.g., "create-form")
     * @param formData The FormData object containing errors and formData
     */
    fun formError(
        template: String,
        fragmentName: String,
        formData: app.epistola.suite.htmx.FormData,
    ) {
        fragment(template, fragmentName) {
            "formData" to formData.formData
            "errors" to formData.errors
        }
        reswap(HxSwap.OUTER_HTML)
    }

    /**
     * Triggers a client-side event after the response is processed.
     *
     * @param event The event name to trigger
     * @param detail Optional JSON detail to include with the event
     */
    fun trigger(event: String, detail: String? = null) {
        val value = if (detail != null) {
            """{"$event": $detail}"""
        } else {
            event
        }
        headers["HX-Trigger"] = value
    }

    /**
     * Pushes a new URL to the browser history.
     *
     * @param url The URL to push
     */
    fun pushUrl(url: String) {
        headers["HX-Push-Url"] = url
    }

    /**
     * Replaces the current URL in the browser history (no new history entry).
     *
     * @param url The URL to replace with
     */
    fun replaceUrl(url: String) {
        headers["HX-Replace-Url"] = url
    }

    /**
     * Overrides the hx-swap attribute for this response.
     *
     * @param swap The swap mode to use
     */
    fun reswap(swap: HxSwap) {
        headers["HX-Reswap"] = swap.value
    }

    /**
     * Overrides the hx-target attribute for this response.
     *
     * @param selector CSS selector for the new target
     */
    fun retarget(selector: String) {
        headers["HX-Retarget"] = selector
    }

    /**
     * Sets a handler for non-HTMX requests using a builder DSL.
     * Allows rendering pages or redirects.
     *
     * Usage:
     * ```kotlin
     * onNonHtmx {
     *     page("templates/list") {
     *         "templates" to templates
     *     }
     * }
     *
     * onNonHtmx {
     *     redirect("/templates")
     * }
     * ```
     *
     * @param block Lambda to build the non-HTMX response
     */
    fun onNonHtmx(block: NonHtmxBuilder.() -> Unit) {
        nonHtmxHandler = {
            NonHtmxBuilder().apply(block).build()
                ?: throw IllegalStateException("onNonHtmx block must call either page() or redirect()")
        }
    }

    /**
     * Sets a handler for non-HTMX requests using a lambda.
     * Legacy API - prefer onNonHtmx(block) for new code.
     *
     * @param handler Lambda that returns a ServerResponse for non-HTMX requests
     */
    @Deprecated(
        "Use onNonHtmx { page(...) } or onNonHtmx { redirect(...) } instead",
        ReplaceWith("onNonHtmx { redirect(url) }"),
    )
    fun onNonHtmxLegacy(handler: () -> ServerResponse) {
        nonHtmxHandler = handler
    }

    /**
     * Builds the final ServerResponse.
     *
     * For HTMX requests: renders fragments with appropriate headers.
     * For non-HTMX requests: executes the nonHtmxHandler or renders full template.
     */
    internal fun build(): ServerResponse {
        if (!request.isHtmx) {
            return nonHtmxHandler?.invoke()
                ?: fullTemplate?.let { ServerResponse.ok().render(it, mergedModel()) }
                ?: throw IllegalStateException("No fragment or nonHtmxHandler defined")
        }

        // For HTMX requests, render fragments
        val primaryFragments = fragments.filter { !it.isOob }
        val oobFragments = fragments.filter { it.isOob }

        // If we have OOB fragments, we need to use the custom view
        return if (oobFragments.isNotEmpty()) {
            buildMultiFragmentResponse(primaryFragments, oobFragments)
        } else {
            buildSingleFragmentResponse(primaryFragments.firstOrNull())
        }
    }

    private fun buildSingleFragmentResponse(fragment: HtmxFragment?): ServerResponse {
        val templatePath = fragment?.let {
            if (it.fragmentName != null) "${it.template} :: ${it.fragmentName}" else it.template
        } ?: fullTemplate ?: throw IllegalStateException("No fragment defined")

        val model = fragment?.model ?: emptyMap()

        var response = ServerResponse.ok()
        headers.forEach { (key, value) -> response = response.header(key, value) }
        return response.render(templatePath, model)
    }

    private fun buildMultiFragmentResponse(
        primaryFragments: List<HtmxFragment>,
        oobFragments: List<HtmxFragment>,
    ): ServerResponse {
        val allFragments = primaryFragments + oobFragments

        var response = ServerResponse.ok()
        headers.forEach { (key, value) -> response = response.header(key, value) }

        return response.render(
            HtmxFragmentsView.VIEW_NAME,
            mapOf(HtmxFragmentsView.FRAGMENTS_KEY to allFragments),
        )
    }

    private fun mergedModel(): Map<String, Any> = fragments.flatMap { it.model.entries }.associate { it.key to it.value }
}

/**
 * Convenience function for creating a redirect response.
 * Use within `onNonHtmx { }` block.
 *
 * @param url The URL to redirect to
 * @return A 303 See Other response
 */
fun redirect(url: String): ServerResponse = ServerResponse.seeOther(URI.create(url)).build()
