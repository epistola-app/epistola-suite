package app.epistola.suite.users.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.TenantRole
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Query to find a user by external ID and auth provider.
 *
 * Returns the user with all tenant memberships, or null if not found.
 */
data class GetUserByExternalId(
    val externalId: String,
    val provider: AuthProvider,
) : Query<User?>

@Component
class GetUserByExternalIdHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetUserByExternalId, User?> {
    override fun handle(query: GetUserByExternalId): User? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT u.id, u.external_id, u.email, u.display_name, u.provider, u.enabled,
                   u.created_at, u.last_login_at,
                   COALESCE(array_agg(tm.tenant_key) FILTER (WHERE tm.tenant_key IS NOT NULL), ARRAY[]::varchar[]) as tenant_keys
            FROM users u
            LEFT JOIN tenant_memberships tm ON u.id = tm.user_id
            WHERE u.external_id = :externalId AND u.provider = :provider
            GROUP BY u.id, u.external_id, u.email, u.display_name, u.provider, u.enabled,
                     u.created_at, u.last_login_at
            """,
        )
            .bind("externalId", query.externalId)
            .bind("provider", query.provider.name)
            .map { rs, _ ->
                val tenantMemberships = (rs.getArray("tenant_keys").array as Array<*>)
                    .filterIsInstance<String>()
                    .map { TenantKey.of(it) }
                    .associateWith { TenantRole.MEMBER }

                User(
                    id = UserKey.of(rs.getObject("id") as java.util.UUID),
                    externalId = rs.getString("external_id"),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    provider = AuthProvider.valueOf(rs.getString("provider")),
                    tenantMemberships = tenantMemberships,
                    enabled = rs.getBoolean("enabled"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    lastLoginAt = rs.getObject("last_login_at", OffsetDateTime::class.java),
                )
            }
            .findOne()
            .orElse(null)
    }
}
