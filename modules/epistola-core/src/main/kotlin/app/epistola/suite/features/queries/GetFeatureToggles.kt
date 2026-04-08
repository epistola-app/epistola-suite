package app.epistola.suite.features.queries

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

data class GetFeatureToggles(
    override val tenantKey: TenantKey,
) : Query<Map<FeatureKey, Boolean>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class GetFeatureTogglesHandler(
    private val featureToggleService: FeatureToggleService,
) : QueryHandler<GetFeatureToggles, Map<FeatureKey, Boolean>> {
    override fun handle(query: GetFeatureToggles): Map<FeatureKey, Boolean> = featureToggleService.resolveAll(query.tenantKey)
}
