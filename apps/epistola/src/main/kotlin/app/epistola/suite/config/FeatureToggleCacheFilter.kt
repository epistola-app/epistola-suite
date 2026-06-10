package app.epistola.suite.config

import app.epistola.suite.features.FeatureToggleService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Binds a per-request feature-toggle cache (see [FeatureToggleService.withRequestCache]) so a
 * single page render — which checks several toggles across the nav, footer and editor — issues at
 * most one toggle query per tenant instead of one per check.
 *
 * Runs just inside [MediatorFilter] so the cache is bound for the whole request lifecycle. Like the
 * other [ScopedValue]-binding filters it also rebinds on async re-dispatches (a fresh empty cache
 * per dispatch is correct — the dispatch simply re-resolves).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class FeatureToggleCacheFilter(
    private val featureToggleService: FeatureToggleService,
) : OncePerRequestFilter() {

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        featureToggleService.withRequestCache {
            filterChain.doFilter(request, response)
        }
    }
}
