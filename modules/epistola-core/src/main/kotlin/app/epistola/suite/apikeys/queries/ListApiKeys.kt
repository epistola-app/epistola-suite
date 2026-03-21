package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

data class ListApiKeys(
    val tenantId: TenantKey,
) : Query<List<ApiKey>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_USERS
    override val tenantKey get() = tenantId
}

@Component
class ListApiKeysHandler(
    private val apiKeyRepository: ApiKeyRepository,
) : QueryHandler<ListApiKeys, List<ApiKey>> {

    override fun handle(query: ListApiKeys): List<ApiKey> = apiKeyRepository.listByTenantId(query.tenantId)
}
