package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class SaveFeedbackConfig(
    val tenantKey: TenantKey,
    val enabled: Boolean,
    val installationId: Long?,
    val repoOwner: String?,
    val repoName: String?,
    val label: String?,
) : Command<FeedbackConfig>

@Component
class SaveFeedbackConfigHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SaveFeedbackConfig, FeedbackConfig> {
    override fun handle(command: SaveFeedbackConfig): FeedbackConfig = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            INSERT INTO feedback_config (tenant_key, enabled, installation_id, repo_owner, repo_name, label)
            VALUES (:tenantKey, :enabled, :installationId, :repoOwner, :repoName, :label)
            ON CONFLICT (tenant_key) DO UPDATE SET
                enabled = EXCLUDED.enabled,
                installation_id = EXCLUDED.installation_id,
                repo_owner = EXCLUDED.repo_owner,
                repo_name = EXCLUDED.repo_name,
                label = EXCLUDED.label
            RETURNING *
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("enabled", command.enabled)
            .bind("installationId", command.installationId)
            .bind("repoOwner", command.repoOwner)
            .bind("repoName", command.repoName)
            .bind("label", command.label)
            .mapTo(FeedbackConfig::class.java)
            .one()
    }
}
