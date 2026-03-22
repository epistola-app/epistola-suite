package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackPriority
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class CreateFeedback(
    val id: FeedbackId,
    val title: String,
    val description: String,
    val category: FeedbackCategory,
    val priority: FeedbackPriority,
    val sourceUrl: String?,
    val consoleLogs: String?,
    val metadata: String?,
    val createdBy: UserKey,
) : Command<Feedback>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = id.tenantKey

    init {
        require(title.isNotBlank()) { "Title is required" }
        require(description.isNotBlank()) { "Description is required" }
    }
}

@Component
class CreateFeedbackHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateFeedback, Feedback> {
    override fun handle(command: CreateFeedback): Feedback {
        val feedbackKey = command.id.key
        val tenantKey = command.id.tenantKey

        // Check if external sync is configured for this tenant
        val config = GetFeedbackSyncConfig(tenantKey).query()
        val syncStatus = if (config?.enabled == true) {
            SyncStatus.PENDING
        } else {
            SyncStatus.NOT_CONFIGURED
        }

        return jdbi.withHandleUnchecked { handle ->
            handle.createQuery(
                """
                INSERT INTO feedback (
                    tenant_key, id, title, description, category, status, priority,
                    source_url, console_logs, metadata, created_by,
                    created_at, updated_at, sync_status
                )
                VALUES (
                    :tenantKey, :id, :title, :description, :category, :status, :priority,
                    :sourceUrl, :consoleLogs, CAST(:metadata AS JSONB), :createdBy,
                    NOW(), NOW(), :syncStatus
                )
                RETURNING tenant_key, id, title, description, category, status, priority,
                          source_url, console_logs, metadata, created_by,
                          created_at, updated_at, external_ref, external_url, sync_status, sync_attempts
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("id", feedbackKey.value)
                .bind("title", command.title)
                .bind("description", command.description)
                .bind("category", command.category.name)
                .bind("status", FeedbackStatus.OPEN.name)
                .bind("priority", command.priority.name)
                .bind("sourceUrl", command.sourceUrl)
                .bind("consoleLogs", command.consoleLogs)
                .bind("metadata", command.metadata)
                .bind("createdBy", command.createdBy.value)
                .bind("syncStatus", syncStatus.name)
                .mapTo(Feedback::class.java)
                .one()
        }
    }
}
