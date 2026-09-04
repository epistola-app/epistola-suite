// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import app.epistola.suite.security.PostLoginTargetResolver
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.savedrequest.HttpSessionRequestCache

/**
 * Where a demo login lands, and — just as important — where it does not.
 *
 * The tenant list **is** `/`; there is no `GET /tenants`, and "Switch tenant" in the nav points at
 * `/`. So this has to be a post-login decision rather than a redirect on that route, or the switcher
 * stops working and the shared `demo` tenant becomes unreachable. `DemoTenantListReachableIT` guards
 * the other half of that.
 */
@Tag("unit")
class DemoPostLoginTargetTest {

    private val target = DemoPostLoginTarget()

    private fun principal(email: String, tenants: List<TenantKey> = emptyList()) = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-0000000000aa"),
        externalId = "demo-post-login",
        email = email,
        displayName = "Demo",
        tenantMemberships = tenants.associateWith { TenantRole.entries.toSet() },
        currentTenantId = null,
    )

    private fun keyFor(email: String) = DemoLoginMembershipResolver.deriveTenantKeyFromEmail(email)!!

    private fun landing(resolver: PostLoginTargetResolver?, auth: Authentication, savedRequestFor: String? = null): String? {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        savedRequestFor?.let {
            val cache = HttpSessionRequestCache()
            val original = MockHttpServletRequest("GET", it).apply { session = request.session }
            cache.saveRequest(original, MockHttpServletResponse())
        }
        PopupAwareAuthenticationSuccessHandler(resolver).onAuthenticationSuccess(request, response, auth)
        return response.redirectedUrl
    }

    private fun authFor(email: String, tenants: List<TenantKey> = emptyList()) = TestingAuthenticationToken(principal(email, tenants), "N/A", listOf())

    @Test
    fun `lands a demo user in their own tenant`() {
        val email = "landing@demo.test"

        assertThat(target.resolve(principal(email, listOf(keyFor(email)))))
            .isEqualTo("/tenants/${keyFor(email).value}")
    }

    @Test
    fun `declines when the principal has no personal tenant`() {
        // Memberships came from the identity provider rather than the demo resolver — there is no
        // "their own" tenant to land in, so login falls back to the tenant list.
        assertThat(target.resolve(principal("landing@demo.test", listOf(TenantKey.of("some-other-tenant"))))).isNull()
    }

    @Test
    fun `declines when no key can be derived`() {
        assertThat(target.resolve(principal("not-an-email"))).isNull()
    }

    @Test
    fun `a plain login lands on the resolved tenant`() {
        val email = "landing@demo.test"

        assertThat(landing(target, authFor(email, listOf(keyFor(email)))))
            .isEqualTo("/tenants/${keyFor(email).value}")
    }

    @Test
    fun `a deep link the user was bounced off still wins`() {
        // The resolver decides the *default* target only. Anything else would swallow the page
        // someone was actually trying to reach.
        val email = "landing@demo.test"

        val landed = landing(target, authFor(email, listOf(keyFor(email))), savedRequestFor = "/tenants/demo/templates")

        // The `?continue` marker is the request cache's own; what matters is the path.
        assertThat(landed).contains("/tenants/demo/templates")
        assertThat(landed).doesNotContain(keyFor(email).value)
    }

    @Test
    fun `without a resolver the default is unchanged`() {
        assertThat(landing(null, authFor("landing@demo.test"))).isEqualTo("/")
    }

    @Test
    fun `a resolver with no opinion leaves the default alone`() {
        assertThat(landing({ null }, authFor("landing@demo.test"))).isEqualTo("/")
    }
}
