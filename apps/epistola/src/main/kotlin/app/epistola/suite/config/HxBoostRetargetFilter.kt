package app.epistola.suite.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Forces every boosted navigation to replace the `<body>` with the full page.
 *
 * `layout/shell.html` sets `hx-boost="true"` on `<body>`, so all plain `<a>`/`<form>`
 * navigation is a boosted AJAX request. HTMX's `hx-target`/`hx-swap` are **inherited**
 * attributes, so a control nested inside a form that scopes its swap to a sub-region —
 * e.g. a Cancel link inside `<form hx-target="#form-area" hx-swap="outerHTML">` — would
 * inherit that target and swap the next full page into the small form `<div>` (a "nested
 * shell").
 *
 * This filter enforces the app's invariant centrally instead of per-form: a boosted
 * navigation always means "render a full page into `<body>`". By returning
 * `HX-Retarget: body` + `HX-Reswap: innerHTML`, any inherited form-level target/swap is
 * overridden on the response, so links inside `hx-target` forms need no special attributes.
 *
 * **Safe for fragment-returning forms.** `HX-Boosted: true` is sent only for boost-driven
 * plain `<a>`/`<form>` navigation. Explicit `hx-post`/`hx-get` elements (all inline-error
 * forms, cascading dropdowns) are NOT boosted — that distinction is exactly what
 * [app.epistola.suite.htmx.HtmxResponseBuilder] (`!isHtmx || htmxBoosted` → full page) and
 * `HtmxRender` (`isHtmx && !htmxBoosted` → fragment) rely on to return fragments. Those
 * requests carry no `HX-Boosted` header, so this filter never touches their responses.
 *
 * @see app.epistola.suite.htmx.htmxBoosted
 */
@Component
class HxBoostRetargetFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.getHeader("HX-Boosted") == "true") {
            response.setHeader("HX-Retarget", "body")
            response.setHeader("HX-Reswap", "innerHTML")
        }
        filterChain.doFilter(request, response)
    }
}
