package app.epistola.suite.security

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.ApiKeyId
import app.epistola.suite.common.ids.TenantId
import org.jdbi.v3.core.Jdbi

/**
 * In-memory API key repository for unit tests that don't need Spring context.
 *
 * All repository methods are overridden to use the in-memory store,
 * so the Jdbi instance is never actually used.
 */
class TestApiKeyRepository(
    private val keys: MutableMap<String, ApiKey>,
) : ApiKeyRepository(
    jdbi = Jdbi.create { throw UnsupportedOperationException("Test Jdbi should not connect") },
) {
    override fun findByKeyHash(keyHash: String): ApiKey? = keys[keyHash]

    override fun updateLastUsed(id: ApiKeyId) {}

    override fun insert(apiKey: ApiKey, keyHash: String) {
        keys[keyHash] = apiKey
    }

    override fun listByTenantId(tenantId: TenantId): List<ApiKey> = keys.values.filter { it.tenantId == tenantId }

    override fun disable(id: ApiKeyId): Boolean {
        val entry = keys.entries.find { it.value.id == id } ?: return false
        keys[entry.key] = entry.value.copy(enabled = false)
        return true
    }
}
