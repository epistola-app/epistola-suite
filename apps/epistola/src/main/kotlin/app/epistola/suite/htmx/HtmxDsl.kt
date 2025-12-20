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
     * Sets a handler for non-HTMX requests.
     * Typically used to redirect after form submission.
     *
     * @param handler Lambda that returns a ServerResponse for non-HTMX requests
     */
    fun onNonHtmx(handler: () -> ServerResponse) {
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
