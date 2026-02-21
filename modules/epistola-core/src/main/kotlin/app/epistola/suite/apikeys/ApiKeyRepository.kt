package app.epistola.suite.apikeys

import app.epistola.suite.common.ids.ApiKeyId
import app.epistola.suite.common.ids.TenantId

/**
 * Repository for API key persistence.
 *
 * Uses direct access (not CQRS-mediated) because the API key authentication
 * filter runs before MediatorContext is bound for the request.
 */
interface ApiKeyRepository {
    fun findByKeyHash(keyHash: String): ApiKey?
    fun updateLastUsed(id: ApiKeyId)
    fun insert(apiKey: ApiKey, keyHash: String)
    fun listByTenantId(tenantId: TenantId): List<ApiKey>
    fun disable(id: ApiKeyId): Boolean
}
