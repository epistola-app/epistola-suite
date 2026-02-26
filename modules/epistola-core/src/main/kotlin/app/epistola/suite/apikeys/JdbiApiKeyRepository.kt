package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Repository

/**
 * JDBI-backed implementation of [ApiKeyRepository].
 */
@Repository
class JdbiApiKeyRepository(
    private val jdbi: Jdbi,
) : ApiKeyRepository {

    override fun findByKeyHash(keyHash: String): ApiKey? = jdbi.withHandle<ApiKey?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, name, key_prefix, enabled, created_at,
                   last_used_at, expires_at, created_by
            FROM api_keys
            WHERE key_hash = :keyHash
            """,
        )
            .bind("keyHash", keyHash)
            .mapTo<ApiKey>()
            .findOne()
            .orElse(null)
    }

    override fun updateLastUsed(id: ApiKeyKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE api_keys SET last_used_at = NOW() WHERE id = :id
                """,
            )
                .bind("id", id)
                .execute()
        }
    }

    override fun insert(
        apiKey: ApiKey,
        keyHash: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO api_keys (id, tenant_id, name, key_hash, key_prefix, enabled,
                                      created_at, expires_at, created_by)
                VALUES (:id, :tenantId, :name, :keyHash, :keyPrefix, :enabled,
                        :createdAt, :expiresAt, :createdBy)
                """,
            )
                .bind("id", apiKey.id)
                .bind("tenantId", apiKey.tenantId)
                .bind("name", apiKey.name)
                .bind("keyHash", keyHash)
                .bind("keyPrefix", apiKey.keyPrefix)
                .bind("enabled", apiKey.enabled)
                .bind("createdAt", apiKey.createdAt)
                .bind("expiresAt", apiKey.expiresAt)
                .bind("createdBy", apiKey.createdBy?.value)
                .execute()
        }
    }

    override fun listByTenantId(tenantId: TenantKey): List<ApiKey> = jdbi.withHandle<List<ApiKey>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, name, key_prefix, enabled, created_at,
                   last_used_at, expires_at, created_by
            FROM api_keys
            WHERE tenant_id = :tenantId
            ORDER BY created_at DESC
            """,
        )
            .bind("tenantId", tenantId)
            .mapTo<ApiKey>()
            .list()
    }

    override fun disable(id: ApiKeyKey): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE api_keys SET enabled = false WHERE id = :id
            """,
        )
            .bind("id", id)
            .execute() > 0
    }
}
