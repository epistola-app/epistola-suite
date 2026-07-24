// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.commands.CreateUser
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Verifies the audit columns (`created_by` / `updated_by`) added by the
 * schema-standardization refactor:
 *
 *  - `created_by` and `updated_by` are stamped with the acting user on insert.
 *  - `updated_by` is re-stamped (and `created_by` is left untouched) on update,
 *    even when a different user performs the update.
 *  - The `users` FK uses `ON DELETE SET NULL`: deleting the creating user nulls
 *    `created_by` but keeps the owning row intact.
 */
class AuditColumnsIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun principalWithFullAccess(userId: UserKey, externalId: String) = EpistolaPrincipal(
        userId = userId,
        externalId = externalId,
        email = "$externalId@example.com",
        displayName = externalId,
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        currentTenantId = null,
    )

    private fun <T> asUser(principal: EpistolaPrincipal, block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(principal, block)
    }

    private fun readAuditColumns(themeId: ThemeId): Pair<UUID?, UUID?> = jdbi.withHandle<Pair<UUID?, UUID?>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT created_by, updated_by
                FROM themes
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", themeId.key)
            .bind("tenantId", themeId.tenantKey)
            .map { rs, _ ->
                rs.getObject("created_by", UUID::class.java) to
                    rs.getObject("updated_by", UUID::class.java)
            }
            .one()
    }

    @Test
    fun `created_by and updated_by are stamped, re-stamped on update, and nulled on user delete`() {
        val tenant = createTenant("Audit Test Tenant")
        val tenantId = TenantId(tenant.id)
        val themeId = ThemeId(ThemeKey.of("audit-theme"), CatalogId.default(tenantId))

        // Provision two distinct, dedicated users (real rows in `users`). We use
        // throwaway users rather than the shared seeded test principal so that
        // deleting the creator in step 4 does not corrupt shared test state.
        val userAModel = asUser(testUser) {
            mediator.send(
                CreateUser(
                    externalId = "audit-user-a",
                    email = "audit-user-a@example.com",
                    displayName = "Audit User A",
                    provider = AuthProvider.LOCAL,
                ),
            )
        }
        val userBModel = asUser(testUser) {
            mediator.send(
                CreateUser(
                    externalId = "audit-user-b",
                    email = "audit-user-b@example.com",
                    displayName = "Audit User B",
                    provider = AuthProvider.LOCAL,
                ),
            )
        }
        val userAId = userAModel.id.value
        val userBId = userBModel.id.value
        assertThat(userBId).isNotEqualTo(userAId)

        // 1. User A creates a theme.
        asUser(principalWithFullAccess(userAModel.id, "audit-user-a")) {
            mediator.send(CreateTheme(id = themeId, name = "Audited Theme"))
        }

        val afterCreate = readAuditColumns(themeId)
        assertThat(afterCreate.first).isEqualTo(userAId) // created_by == A
        assertThat(afterCreate.second).isEqualTo(userAId) // updated_by == A

        // 2-3. User B updates the theme.
        asUser(principalWithFullAccess(userBModel.id, "audit-user-b")) {
            mediator.send(UpdateTheme(id = themeId, name = "Audited Theme (edited by B)"))
        }

        val afterUpdate = readAuditColumns(themeId)
        assertThat(afterUpdate.first).isEqualTo(userAId) // created_by unchanged == A
        assertThat(afterUpdate.second).isEqualTo(userBId) // updated_by == B

        // 4. Delete user A from `users`. The FK is ON DELETE SET NULL, so the
        //    theme row must remain but its created_by becomes NULL.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM users WHERE id = :id")
                .bind("id", userAId)
                .execute()
        }

        val afterDelete = readAuditColumns(themeId)
        assertThat(afterDelete.first).isNull() // created_by SET NULL
        assertThat(afterDelete.second).isEqualTo(userBId) // updated_by still B

        // Row is still present and readable.
        val stillThere = asUser(principalWithFullAccess(userBModel.id, "audit-user-b")) {
            mediator.query(app.epistola.suite.themes.queries.GetTheme(id = themeId))
        }
        assertThat(stillThere).isNotNull
        assertThat(stillThere!!.name).isEqualTo("Audited Theme (edited by B)")
    }
}
