package app.epistola.suite.features.commands

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class SaveFeatureToggle(
    override val tenantKey: TenantKey,
    val featureKey: FeatureKey,
    val enabled: Boolean,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class SaveFeatureToggleHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SaveFeatureToggle, Unit> {
    override fun handle(command: SaveFeatureToggle) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO feature_toggles (tenant_key, feature_key, enabled)
                VALUES (:tenantKey, :featureKey, :enabled)
                ON CONFLICT (tenant_key, feature_key)
                DO UPDATE SET enabled = :enabled
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("featureKey", command.featureKey)
                .bind("enabled", command.enabled)
                .execute()
        }
    }
}
