package app.epistola.suite.feedback

import app.epistola.suite.backup.TenantBackupTableContributor
import org.springframework.stereotype.Component

/**
 * Declares the feedback feature's tenant-scoped tables to tenant backup/restore.
 * Feedback (the captured items, their comments, and attachment metadata) is part
 * of a tenant's authoring data, so it is **included** (merge-restored). The backup
 * topology collects this contribution — the feedback module owns the classification
 * of its own tables instead of the backup module hard-coding their names.
 */
@Component
class FeedbackBackupTables : TenantBackupTableContributor {
    override fun includedTables(): Set<String> = setOf("feedback", "feedback_comments", "feedback_assets")
}
