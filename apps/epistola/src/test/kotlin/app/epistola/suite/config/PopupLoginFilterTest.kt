// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler.Companion.POPUP_SESSION_ATTR
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.session.MapSessionRepository
import org.springframework.session.Session
import org.springframework.session.web.http.HeaderHttpSessionIdResolver
import org.springframework.session.web.http.SessionRepositoryFilter
import java.util.concurrent.ConcurrentHashMap

class PopupLoginFilterTest {

    @Test
    fun `filter runs immediately after Spring Session`() {
        val order = PopupLoginFilter::class.java.getAnnotation(Order::class.java).value

        assertThat(order).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER + 1)
    }

    @Test
    fun `popup marker survives OIDC redirect in the Spring-managed session`() {
        val sessions = ConcurrentHashMap<String, Session>()
        val sessionRepositoryFilter = SessionRepositoryFilter(MapSessionRepository(sessions)).apply {
            setHttpSessionIdResolver(HeaderHttpSessionIdResolver.xAuthToken())
        }
        val popupLoginFilter = PopupLoginFilter()

        val loginRequest = MockHttpServletRequest("GET", "/login").apply {
            setParameter("popup", "true")
        }
        val loginResponse = MockHttpServletResponse()

        sessionRepositoryFilter.doFilter(loginRequest, loginResponse) { request, response ->
            popupLoginFilter.doFilter(request, response, FilterChain { _, _ -> })
        }

        val sessionId = requireNotNull(loginResponse.getHeader("X-Auth-Token"))
        assertThat(sessionId).isNotBlank()
        assertThat(sessions.getValue(sessionId).getAttribute<Boolean>(POPUP_SESSION_ATTR)).isTrue()

        val callbackRequest = MockHttpServletRequest("GET", "/login/oauth2/code/oidc").apply {
            addHeader("X-Auth-Token", sessionId)
        }
        val callbackResponse = MockHttpServletResponse()

        sessionRepositoryFilter.doFilter(callbackRequest, callbackResponse) { request, response ->
            PopupAwareAuthenticationSuccessHandler().onAuthenticationSuccess(
                request as HttpServletRequest,
                response as HttpServletResponse,
                TestingAuthenticationToken("user", "password"),
            )
        }

        assertThat(callbackResponse.redirectedUrl)
            .isEqualTo(PopupAwareAuthenticationSuccessHandler.POPUP_SUCCESS_URL)
        assertThat(sessions.getValue(sessionId).getAttribute<Boolean>(POPUP_SESSION_ATTR)).isNull()
    }

    @Test
    fun `normal login keeps the default success redirect`() {
        val request = MockHttpServletRequest("POST", "/login")
        val response = MockHttpServletResponse()

        PopupAwareAuthenticationSuccessHandler().onAuthenticationSuccess(
            request,
            response,
            TestingAuthenticationToken("user", "password"),
        )

        assertThat(response.redirectedUrl).isEqualTo("/")
    }
}
