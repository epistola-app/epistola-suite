package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackSyncConfig
import app.epistola.suite.feedback.SyncProviderType
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class SaveFeedbackSyncConfig(
    override val tenantKey: TenantKey,
    val enabled: Boolean,
    val providerType: SyncProviderType,
    val settings: String,
) : Command<FeedbackSyncConfig>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class SaveFeedbackSyncConfigHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SaveFeedbackSyncConfig, FeedbackSyncConfig> {
    override fun handle(command: SaveFeedbackSyncConfig): FeedbackSyncConfig = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            INSERT INTO feedback_sync_config (tenant_key, enabled, provider_type, settings)
            VALUES (:tenantKey, :enabled, :providerType, CAST(:settings AS JSONB))
            ON CONFLICT (tenant_key) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                provider_type = EXCLUDED.provider_type,
                settings = EXCLUDED.settings
            RETURNING *
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("enabled", command.enabled)
            .bind("providerType", command.providerType.name)
            .bind("settings", command.settings)
            .mapTo(FeedbackSyncConfig::class.java)
            .one()
    }
}
