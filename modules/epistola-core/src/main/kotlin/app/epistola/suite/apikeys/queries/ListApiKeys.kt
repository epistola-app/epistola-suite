package app.epistola.suite.apikeys.queries

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.springframework.stereotype.Component

data class ListApiKeys(
    val tenantId: TenantId,
) : Query<List<ApiKey>>

@Component
class ListApiKeysHandler(
    private val apiKeyRepository: ApiKeyRepository,
) : QueryHandler<ListApiKeys, List<ApiKey>> {

    override fun handle(query: ListApiKeys): List<ApiKey> = apiKeyRepository.listByTenantId(query.tenantId)
}
