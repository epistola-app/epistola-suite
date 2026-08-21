// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_PARAM
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Filter that saves the popup login state to the session when OAuth2 login is initiated.
 *
 * OAuth2 login involves multiple redirects (to IdP and back), so the popup parameter
 * needs to be persisted in the session to survive these redirects.
 *
 * This filter checks for popup=true on OAuth2 authorization requests and saves the
 * state to the session. The PopupAwareAuthenticationSuccessHandler then reads this
 * state after successful authentication.
 *
 * It must run after [SessionRepositoryFilter], otherwise `request.session` refers to
 * the servlet container session instead of Epistola's JDBC-backed Spring Session.
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 1)
class PopupLoginFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Only the login / OAuth2 authorization entry points carry popup=true, so
        // gate the getParameter() call behind this path check. Reading a request
        // parameter forces the servlet to parse the body, and this filter still runs
        // very early; doing that eagerly on every request is wasteful and
        // historically also locked form bodies to ISO-8859-1 (mangling diacritics
        // like `Café`) when it parsed before the encoding was set. The container
        // default is now pinned to UTF-8 up front (see RequestEncodingConfig), so
        // correctness no longer hinges on this — but keeping the read scoped to the
        // paths that actually need it stays the right thing to do. The popup flag
        // travels as a query parameter on these GET entry points anyway.
        val path = request.requestURI
        val isOAuth2Request = path.startsWith("/oauth2/authorization/")
        val isLoginRequest = path == "/login"

        if (isOAuth2Request || isLoginRequest) {
            val isPopup = request.getParameter(POPUP_PARAM) == "true"
            if (isPopup) {
                // Save popup mode to session for use after OAuth2 redirect chain completes
                request.session.setAttribute(POPUP_SESSION_ATTR, true)
            }
        }

        filterChain.doFilter(request, response)
    }
}
