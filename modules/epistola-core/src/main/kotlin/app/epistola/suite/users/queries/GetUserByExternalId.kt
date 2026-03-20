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
        // First get the user
        val user = handle.createQuery(
            """
            SELECT id, external_id, email, display_name, provider, enabled,
                   created_at, last_login_at
            FROM users
            WHERE external_id = :externalId AND provider = :provider
            """,
        )
            .bind("externalId", query.externalId)
            .bind("provider", query.provider.name)
            .map { rs, _ ->
                User(
                    id = UserKey.of(rs.getObject("id") as java.util.UUID),
                    externalId = rs.getString("external_id"),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    provider = AuthProvider.valueOf(rs.getString("provider")),
                    tenantMemberships = emptyMap(), // populated below
                    enabled = rs.getBoolean("enabled"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    lastLoginAt = rs.getObject("last_login_at", OffsetDateTime::class.java),
                )
            }
            .findOne()
            .orElse(null) ?: return@withHandleUnchecked null

        // Then get memberships with roles array
        val memberships = handle.createQuery(
            """
            SELECT tenant_key, roles
            FROM tenant_memberships
            WHERE user_id = :userId
            """,
        )
            .bind("userId", user.id.value)
            .map { rs, _ ->
                val tenantKey = TenantKey.of(rs.getString("tenant_key"))
                val rolesArray = rs.getArray("roles").array as Array<*>
                val roles = rolesArray.filterIsInstance<String>().mapNotNull { roleName ->
                    try {
                        TenantRole.valueOf(roleName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }.toSet()
                tenantKey to roles
            }
            .list()
            .toMap()

        user.copy(tenantMemberships = memberships)
    }
}
