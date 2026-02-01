package app.epistola.suite.config

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that binds the Mediator to the current request scope using ScopedValue.
 *
 * This filter runs at the highest precedence to ensure the mediator is available
 * throughout the entire request lifecycle, including:
 * - Controllers and handlers
 * - Exception handlers
 * - Other filters that run after this one
 *
 * The mediator is automatically unbound when the request completes due to
 * ScopedValue's automatic scope management.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MediatorFilter(
    private val mediator: Mediator,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        MediatorContext.runWithMediator(mediator) {
            filterChain.doFilter(request, response)
        }
    }
}
