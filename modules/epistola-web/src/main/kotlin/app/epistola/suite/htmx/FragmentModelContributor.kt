package app.epistola.suite.htmx

import jakarta.servlet.http.HttpServletRequest

/**
 * SPI for injecting shared model attributes into HTMX fragment renders.
 *
 * The multi-fragment / OOB render path ([HtmxResponseBuilder] `oob(...)`) renders
 * templates through the engine directly, which — unlike a normal MVC view render
 * via `ServerResponse.render()` — does NOT run `HandlerInterceptor`s. So the
 * global attributes those interceptors add (e.g. `auth`, `isManager`, feature
 * flags, footer chrome) are absent, and any fragment that references them breaks.
 *
 * A host app contributes those globals by implementing this interface as a
 * `@Component`; [HtmxResponseBuilder] collects every implementation from the
 * application context and merges their output into each fragment's model.
 *
 * Contract: **the fragment's own model always wins** — a contributor must never
 * override a key the handler explicitly set. Contributions are additive globals,
 * not overrides.
 */
interface FragmentModelContributor {
    /**
     * Extra model attributes to merge into [fragmentModel] before rendering.
     * Keys already present in [fragmentModel] take precedence and must not be
     * clobbered by the caller.
     *
     * @param request the current HTTP request (for host apps that read request
     *   state; implementations may read the tenant from [fragmentModel] instead).
     * @param fragmentModel the model the handler built for this fragment.
     */
    fun contribute(request: HttpServletRequest, fragmentModel: Map<String, Any?>): Map<String, Any?>
}
