// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * A log line's tenant is the principal's current tenant (docs/application-logs.md). A user signed in
 * to one tenant and working in another must count as working in that other one, or their work is
 * logged under the tenant they signed in to.
 */
class SecurityFilterTenantTest {

    private val filter = SecurityFilter()
    private val home = TenantKey.of("home")
    private val other = TenantKey.of("other")

    @AfterEach
    fun clearHolder() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `a UI route of another tenant the user belongs to makes it their current tenant`() {
        assertThat(currentTenantDuring("/tenants/other/catalogs", principal(listOf(home, other)))).isEqualTo(other)
    }

    @Test
    fun `a tenant the user may not act in leaves their current tenant`() {
        assertThat(currentTenantDuring("/tenants/other/catalogs", principal(listOf(home)))).isEqualTo(home)
    }

    @Test
    fun `an API route leaves the tenant its key or token was bound to`() {
        assertThat(currentTenantDuring("/api/tenants/other/catalogs", principal(listOf(home, other)))).isEqualTo(home)
    }

    private fun currentTenantDuring(path: String, principal: EpistolaPrincipal): TenantKey? {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        var current: TenantKey? = null
        filter.doFilter(MockHttpServletRequest("GET", path), MockHttpServletResponse(), FilterChain { _, _ -> current = SecurityContext.current().currentTenantId })
        return current
    }

    private fun principal(memberOf: List<TenantKey>) = EpistolaPrincipal(
        userId = UserKey.of(UUID.randomUUID()),
        externalId = "test",
        email = "test@example.com",
        displayName = "Test",
        tenantMemberships = memberOf.associateWith { setOf(TenantRole.CONTENT_VIEWER) },
        currentTenantId = memberOf.first(),
    )
}
