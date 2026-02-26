package app.epistola.suite.security

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey

/**
 * In-memory API key repository for unit tests that don't need Spring context.
 */
class TestApiKeyRepository(
    private val keys: MutableMap<String, ApiKey> = mutableMapOf(),
) : ApiKeyRepository {
    override fun findByKeyHash(keyHash: String): ApiKey? = keys[keyHash]

    override fun updateLastUsed(id: ApiKeyKey) {}

    override fun insert(apiKey: ApiKey, keyHash: String) {
        keys[keyHash] = apiKey
    }

    override fun listByTenantId(tenantId: TenantKey): List<ApiKey> = keys.values.filter { it.tenantId == tenantId }

    override fun disable(id: ApiKeyKey): Boolean {
        val entry = keys.entries.find { it.value.id == id } ?: return false
        keys[entry.key] = entry.value.copy(enabled = false)
        return true
    }
}
