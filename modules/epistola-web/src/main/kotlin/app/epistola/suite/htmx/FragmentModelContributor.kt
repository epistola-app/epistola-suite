// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * not overrides. They are also **request-scoped**: [contribute] is called ONCE
 * per response (against the union of all fragment models), not once per
 * fragment, so an implementation that queries (e.g. resolving the tenant) pays
 * once however many fragments the response carries.
 */
interface FragmentModelContributor {
    /**
     * Extra model attributes to merge into every fragment's model before
     * rendering. Keys already present in a fragment's own model take precedence
     * and must not be clobbered by the caller.
     *
     * @param request the current HTTP request (for host apps that read request
     *   state; implementations may read the tenant from [fragmentModel] instead).
     * @param fragmentModel the union of the models of all fragments in the
     *   response (first fragment wins on duplicate keys).
     */
    fun contribute(request: HttpServletRequest, fragmentModel: Map<String, Any?>): Map<String, Any?>
}
