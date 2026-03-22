package app.epistola.suite.users.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.TenantRole
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Syncs tenant memberships from the IDP (JWT/OIDC) into the `tenant_memberships` table.
 *
 * Upserts rows with current roles and updates `last_synced_at`.
 * This keeps the DB current for API key fallback and audit purposes.
 */
data class SyncTenantMemberships(
    val userId: UserKey,
    val memberships: Map<TenantKey, Set<TenantRole>>,
) : Command<Unit>,
    SystemInternal

@Component
class SyncTenantMembershipsHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncTenantMemberships, Unit> {

    @Transactional
    override fun handle(command: SyncTenantMemberships) {
        jdbi.withHandleUnchecked { handle ->
            for ((tenantKey, roles) in command.memberships) {
                val rolesArray = roles.map { it.name }.toTypedArray()

                handle.createUpdate(
                    """
                    INSERT INTO tenant_memberships (user_id, tenant_key, roles, last_synced_at)
                    VALUES (:userId, :tenantKey, :roles::varchar[], NOW())
                    ON CONFLICT (user_id, tenant_key)
                    DO UPDATE SET roles = :roles::varchar[], last_synced_at = NOW()
                    """,
                )
                    .bind("userId", command.userId.value)
                    .bind("tenantKey", tenantKey.value)
                    .bind("roles", rolesArray)
                    .execute()
            }
        }
    }
}
