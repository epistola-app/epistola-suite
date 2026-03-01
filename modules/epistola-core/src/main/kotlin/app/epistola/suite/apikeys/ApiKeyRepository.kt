package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey

/**
 * Repository for API key persistence.
 *
 * Uses direct access (not CQRS-mediated) because the API key authentication
 * filter runs before MediatorContext is bound for the request.
 */
interface ApiKeyRepository {
    fun findByKeyHash(keyHash: String): ApiKey?
    fun updateLastUsed(id: ApiKeyKey)
    fun insert(apiKey: ApiKey, keyHash: String)
    fun listByTenantId(tenantId: TenantKey): List<ApiKey>
    fun disable(id: ApiKeyKey): Boolean
}
