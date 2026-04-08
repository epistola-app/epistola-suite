package app.epistola.suite.features.commands

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteFeatureToggle(
    override val tenantKey: TenantKey,
    val featureKey: FeatureKey,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class DeleteFeatureToggleHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteFeatureToggle, Unit> {
    override fun handle(command: DeleteFeatureToggle) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "DELETE FROM feature_toggles WHERE tenant_key = :tenantKey AND feature_key = :featureKey",
            )
                .bind("tenantKey", command.tenantKey)
                .bind("featureKey", command.featureKey)
                .execute()
        }
    }
}
