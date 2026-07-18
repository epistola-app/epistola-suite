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
    val model: Map<String, Any?>,
    val isOob: Boolean = false,
)

/**
 * Builder for constructing model maps in a DSL-friendly way.
 *
 * Two independent mechanisms guard the DSL — they solve different problems and
 * neither replaces the other:
 *
 * 1. `@HtmxDsl` (a `@DslMarker`) prevents accidental nesting of DSL receivers
 *    (e.g. calling outer-builder methods from inside an inner builder block).
 *    It does **not** influence overload resolution and would not have prevented
 *    the bug below.
 *
 * 2. `infix fun String.to(value: Any?)` accepts nullable values so that
 *    `"key" to nullable` resolves to this member extension. With a non-null
 *    `Any` parameter, Kotlin silently fell through to `kotlin.to` (the stdlib
 *    `Pair` extension) for any nullable expression — the resulting `Pair` was
 *    discarded as an expression-statement and the entry never reached the
 *    model, with no compile-time or runtime signal.
 *
 * Templates that need to tolerate nulls must say so explicitly via Thymeleaf's
 * safe navigation (`?.`) or `th:if` — surfacing intent at the call site.
 */
@HtmxDsl
class ModelBuilder {
    private val model = mutableMapOf<String, Any?>()

    /**
     * Adds a key-value pair to the model.
     * Usage: `"key" to value`
     *
     * Parameter type is `Any?` (not `Any`) — see class KDoc for the overload-resolution
     * rationale. Do not tighten this without reproducing the silent-discard regression.
     */
    infix fun String.to(value: Any?) {
        model[this] = value
    }

    internal fun build(): Map<String, Any?> = model.toMap()
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

    /** The fragments accumulated so far, exposed for assertions in tests. */
    internal val emittedFragments: List<HtmxFragment> get() = fragments.toList()

    private val headers = mutableMapOf<String, String>()
    private var nonHtmxHandler: (() -> ServerResponse)? = null
    private var fullTemplate: String? = null
    private var status: Int = 200
    private var redirectUrl: String? = null

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
     * Sets the HTTP status of the HTMX response (default 200).
     *
     * Note: HTMX ignores 4xx/5xx response bodies unless the response is
     * "shaped" — carries an HX-Reswap header. app-shell.js allows shaped
     * error responses to swap, so pair a non-2xx status with reswap()
     * (globalFormError() does both).
     */
    fun status(code: Int) {
        status = code
    }

    /**
     * Reports an operation-level (non-field) form error into the form's
     * global error slot via an OOB swap, with a real error status.
     *
     * The form must include the shared slot fragment with a matching id:
     * `~{epistola-web/form-error :: form-error(id='create-tenant-error')}`.
     *
     * Sets HX-Reswap to none (no primary swap; the OOB fragment still
     * processes) — app-shell.js recognizes the header and lets the error
     * response swap. Handlers should still provide onNonHtmx { } re-rendering
     * the page with the standardized `error` model key.
     *
     * Usage:
     * ```kotlin
     * return request.htmx {
     *     globalFormError("start-load-test-error", errorMessage)
     *     onNonHtmx { page(422, "loadtest/new") { "error" to errorMessage } }
     * }
     * ```
     *
     * @param errorId The id of the form's error slot element
     * @param message The error message to display
     * @param statusCode The response status (default 422)
     */
    fun globalFormError(
        errorId: String,
        message: String,
        statusCode: Int = 422,
    ) {
        oob("epistola-web/form-error", "form-error-oob") {
            "id" to errorId
            "message" to message
        }
        reswap(HxSwap.NONE)
        status(statusCode)
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

    // ── Dialog-form lifecycle helpers ───────────────────────────────────────
    //
    // The four terminal shapes a dialog-form handler returns, so handlers stop
    // hand-rolling retarget/reswap/trigger. They compose the primitives above;
    // the global `form-error` slot (epistola-web/form-error, embedded inside the
    // dialog) keeps working in every case. See docs/dialog-forms.md for the full
    // route convention and per-shape rationale.
    //
    // Convention they assume: the dialog's <form> submits with the LIST as its
    // hx-target (hx-target="#the-list", hx-swap="outerHTML") — the same shape
    // catalog/variant already use. That makes the happy path a plain list swap
    // and forces every error path to be explicit about NOT letting the swap land
    // in the list (which would destroy the open dialog and the user's input).

    /**
     * Success → refresh the list and CLOSE the dialog.
     *
     * OOB-swaps the list fragment (updates it by id wherever it sits on the
     * page), emits `HX-Trigger("closeDialog")` so the app-shell listener closes
     * the open dialog, and disables the primary swap (`HX-Reswap: none`) so the
     * form's own target is left untouched while the dialog closes. 200.
     *
     * The list fragment MUST render as an OOB swap: its root element needs an id
     * and `hx-swap-oob` (catalog's `catalog-list` fragment is the model — it
     * toggles `hx-swap-oob` via an `oob` flag in the model). This helper injects
     * `"oob" to true` after [model] runs, so callers never have to remember it.
     * Pass whatever else the list fragment needs through [model].
     *
     * [listUrl] is the list's own URL — the one the dialog was opened over. The
     * open pushed the `/…/new` URL via `hx-push-url`, so on success we must put
     * the address bar back to the list. This emits `HX-Replace-Url: <listUrl>` so
     * HTMX performs the replace through its OWN history machinery, keeping its
     * internal current-path in sync — a raw `history.replaceState` (what the
     * dialog close listener does for Cancel/ESC) does NOT tell HTMX, which would
     * leave the pre-open (row-less) list snapshot cached under the list URL and
     * shown stale on Back after a create (CR3). Baked in here rather than left to
     * each caller so it can't be forgotten and reintroduce the bug.
     *
     * Pair with: `onNonHtmx { redirect("/…/list") }` (a full-page submit just
     * lands back on the list).
     */
    fun dialogSuccess(
        listTemplate: String,
        listFragment: String,
        listUrl: String,
        model: ModelBuilder.() -> Unit = {},
    ) {
        oob(listTemplate, listFragment) {
            model()
            "oob" to true
        }
        trigger("closeDialog")
        reswap(HxSwap.NONE)
        replaceUrl(listUrl)
        status(200)
    }

    /**
     * Success that STAYS OPEN (the api-key reveal). Swaps new content into the
     * dialog in place of the form and does NOT close it, so the user can act on
     * the revealed content (copy the one-time key) before dismissing it.
     *
     * Renders [fragmentName] as the primary swap, retargeted to [revealTarget]
     * (the element the reveal panel replaces — e.g. the form-area id the reveal
     * fragment reuses) with `outerHTML`, 200, and — deliberately — NO
     * `closeDialog` trigger. "Success-that-stays-open" falls out of simply
     * omitting the trigger.
     *
     * Pair with: `onNonHtmx { page("…/created") { … } }` (full-page reveal).
     */
    fun dialogReveal(
        template: String,
        fragmentName: String,
        revealTarget: String,
        model: ModelBuilder.() -> Unit = {},
    ) {
        fragment(template, fragmentName, model)
        retarget(revealTarget)
        reswap(HxSwap.OUTER_HTML)
        status(200)
    }

    /**
     * Success → NAVIGATE to a newly created resource (the dialog disappears
     * because the whole page navigates away).
     *
     * Emits `HX-Redirect: <url>` — HTMX performs a client-side, full-page
     * navigation to [url] — with a 200 status and NO body/fragment. Use this for
     * the "create → go straight to the created thing" flow, where the list the
     * dialog sat on is *not* where the user should end up (e.g. creating a
     * template lands the user on the new template's own page). Contrast
     * [dialogSuccess], which STAYS on the list (closes the dialog + OOB-refreshes
     * it in place) for resources that are managed from the list they were created
     * on.
     *
     * No fragment is rendered: `HX-Redirect` supersedes any swap, so there is
     * nothing to retarget/reswap. The dialog is discarded with the old page.
     *
     * Pair with: `onNonHtmx { redirect("/…/created") }` — a full-page (non-HTMX)
     * submit just 303-redirects to the same resource.
     */
    fun dialogRedirect(url: String) {
        redirectUrl = url
        headers["HX-Redirect"] = url
        status(200)
    }

    /**
     * Field-validation errors → re-render the dialog's `<form>` in place with
     * inline errors, retargeted to the form (NOT the list, and NOT the dialog).
     *
     * Spreads `formData` (typed values, preserved) + `errors` (per-field
     * messages) into the form fragment, retargets the `outerHTML` swap to
     * [formTarget] — a stable id on the caller's `<form>` (e.g.
     * `#create-environment-form`) — and returns [statusCode] (422 by default).
     * The re-rendered [fragmentName] MUST be that same `<form id="…">…</form>`,
     * so the swap replaces the form in place.
     *
     * Why the form and not the dialog: the form's hx-target is the list, so a
     * default `outerHTML` re-render would replace the list and destroy the open
     * dialog — hence the retarget. But retargeting the `<dialog>` element itself
     * is also wrong: `outerHTML`-swapping an open modal dialog removes it from
     * the top layer and drops in a fresh, plain (closed) `<dialog>`, and nothing
     * reopens it (the swap targets the dialog id, not the mount, so neither the
     * `htmx:afterSwap` mount handler nor `htmx:load` calls `showModal`) — the
     * dialog would lose its backdrop / close on every validation error. Target
     * the inner form so the `<dialog>` is never touched and stays open/modal —
     * consistent with [dialogReveal], which swaps an inner element too.
     *
     * Do NOT use this for the file uploads (font/image) — re-rendering the form
     * body clears the chosen `<input type=file>`; use [dialogFormError] instead.
     *
     * Pair with: `onNonHtmx { page(422, "…/host") { +formData … } }`
     * (re-render the host page with the dialog embedded and errors shown).
     *
     * [model] supplies any prefill the form fragment needs to re-render itself
     * beyond the field values — e.g. the `tenantId` its `th:hx-post` URL is built
     * from, or attribute descriptors. `formData` + `errors` are added after it,
     * so they always win.
     */
    fun dialogFieldErrors(
        template: String,
        fragmentName: String,
        formTarget: String,
        formData: FormData,
        statusCode: Int = 422,
        model: ModelBuilder.() -> Unit = {},
    ) {
        fragment(template, fragmentName) {
            model()
            "formData" to formData.formData
            "errors" to formData.errors
        }
        retarget(formTarget)
        reswap(HxSwap.OUTER_HTML)
        status(statusCode)
    }

    /**
     * Operation-level / OOB-only error → OOB-swap just the form-error slot,
     * disable the primary swap, real error status. Identical to
     * [globalFormError]; named in the dialog family and documented for the case
     * that needs it most: the multipart UPLOADS (font, image). A browser cannot
     * repopulate `<input type=file>` after a round-trip, so the form body must
     * NOT be re-rendered — the OOB slot updates only the error text and the form
     * (with the user's file selection) stays exactly as they left it.
     *
     * Pair with: `onNonHtmx { page(422, "…/host") { "error" to message } }`.
     */
    fun dialogFormError(
        errorId: String,
        message: String,
        statusCode: Int = 422,
    ) = globalFormError(errorId, message, statusCode)

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
        // A history-restore request (HX-History-Restore-Request) is htmx re-fetching
        // the URL after a local history-cache miss and swapping the response in as the
        // WHOLE page body — not into a target. It must therefore render the full host
        // template / onNonHtmx page, never a bare fragment (which would replace the
        // entire page with, e.g., a lone unopened dialog). Route it through the same
        // full-page branch as non-HTMX and boosted requests.
        if (!request.isHtmx || request.htmxBoosted || request.htmxHistoryRestoreRequest) {
            return nonHtmxHandler?.invoke()
                ?: fullTemplate?.let { ServerResponse.ok().render(it, mergedModel()) }
                ?: throw IllegalStateException("No fragment or nonHtmxHandler defined")
        }

        // Client-side redirect (dialogRedirect): HX-Redirect drives a full-page
        // navigation, so no fragment/body is rendered — just the headers + status.
        redirectUrl?.let {
            var response = ServerResponse.status(status)
            headers.forEach { (key, value) -> response = response.header(key, value) }
            return response.build()
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

        var response = ServerResponse.status(status)
        headers.forEach { (key, value) -> response = response.header(key, value) }
        return response.render(templatePath, model)
    }

    private fun buildMultiFragmentResponse(
        primaryFragments: List<HtmxFragment>,
        oobFragments: List<HtmxFragment>,
    ): ServerResponse {
        val allFragments = primaryFragments + oobFragments

        var builder = ServerResponse.status(status)
        headers.forEach { (key, value) -> builder = builder.header(key, value) }

        // Render all fragments into a single response body using the template engine directly.
        // This avoids depending on a custom ViewResolver which doesn't work reliably
        // with Spring's functional router ServerResponse.render().
        return builder.build { request, response ->
            response.contentType = "text/html;charset=UTF-8"
            val application = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(request.servletContext)
            val webAppContext = org.springframework.web.context.support.WebApplicationContextUtils
                .getRequiredWebApplicationContext(request.servletContext)
            val engine = webAppContext.getBean(org.thymeleaf.spring6.SpringTemplateEngine::class.java)
            // Global model contributors re-inject what HandlerInterceptors would
            // add on a normal view render (this engine-direct path skips them):
            // auth, feature flags, footer chrome, … The fragment's own model
            // always wins (merged last).
            val contributors = webAppContext.getBeansOfType(FragmentModelContributor::class.java).values

            for (fragment in allFragments) {
                val variables: Map<String, Any?> = if (contributors.isEmpty()) {
                    fragment.model
                } else {
                    val merged = LinkedHashMap<String, Any?>()
                    contributors.forEach { merged.putAll(it.contribute(request, fragment.model)) }
                    merged.putAll(fragment.model)
                    merged
                }
                val context = org.thymeleaf.context.WebContext(
                    application.buildExchange(request, response),
                    java.util.Locale.getDefault(),
                    variables,
                )
                val spec = if (fragment.fragmentName != null) {
                    org.thymeleaf.TemplateSpec(
                        fragment.template,
                        setOf(fragment.fragmentName),
                        null as org.thymeleaf.templatemode.TemplateMode?,
                        null as Map<String, Any>?,
                    )
                } else {
                    org.thymeleaf.TemplateSpec(fragment.template, null as org.thymeleaf.templatemode.TemplateMode?)
                }
                response.writer.write(engine.process(spec, context))
            }

            null
        }
    }

    private fun mergedModel(): Map<String, Any?> = fragments.flatMap { it.model.entries }.associate { it.key to it.value }
}

/**
 * Convenience function for creating a redirect response.
 * Use within `onNonHtmx { }` block.
 *
 * @param url The URL to redirect to
 * @return A 303 See Other response
 */
fun redirect(url: String): ServerResponse = ServerResponse.seeOther(URI.create(url)).build()
