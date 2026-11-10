// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.privacy

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A connection's privacy is the installation administrator's: nobody else changes it. */
class SupportPrivacyAuthorizationTest : IntegrationTestBase() {
    @Test
    fun `a tenant administrator does not save a connection's privacy`() {
        val tenantAdministrator = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-0000000c0001"),
            externalId = "privacy-tenant-admin",
            email = "privacy-tenant-admin@example.com",
            displayName = "Tenant Administrator",
            tenantMemberships = emptyMap(),
            globalRoles = setOf(TenantRole.TENANT_ADMINISTRATOR),
            platformRoles = emptySet(),
            currentTenantId = null,
        )

        runAs(tenantAdministrator) {
            assertThatThrownBy { SetSupportConnectionPrivacy(java.util.UUID.randomUUID(), TenantIdentity.NAMED, TelemetryDetail.FULL).execute() }
                .isInstanceOf(PlatformAccessDeniedException::class.java)
        }
    }
}
