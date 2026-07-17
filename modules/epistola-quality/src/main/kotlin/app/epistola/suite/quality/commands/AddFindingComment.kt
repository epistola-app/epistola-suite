package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.QualityFindingCommentKey
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUser
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Adds a comment to a finding — how a reviewer explains what they actually want changed, or how a
 * team argues about whether a check is right.
 *
 * ### Local only
 *
 * Comments never leave this installation in v1. A finding's *disposition* has an obvious meaning to
 * a remote checker ("stop reporting this") and syncs; a comment does not — what a checker should do
 * with "@sander is this still true?" is unsettled, and inventing an answer now would bake it in. The
 * finding id is stable, so a future sync has something to hang off when the question is settled.
 *
 * The comment hangs off the finding's row id, which reconciliation deliberately preserves across a
 * resolve/resurface cycle — so a discussion survives the problem being fixed and coming back.
 */
data class AddFindingComment(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
    val body: String,
) : Command<QualityFindingCommentKey>,
    RequiresPermission {
    init {
        validate("body", body.isNotBlank()) { "A comment is required" }
        validate("body", body.length <= MAX_BODY_LENGTH) { "Comment must be $MAX_BODY_LENGTH characters or less" }
    }

    override val permission: Permission get() = Permission.TEMPLATE_EDIT

    companion object {
        const val MAX_BODY_LENGTH = 4000
    }
}

@Component
class AddFindingCommentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AddFindingComment, QualityFindingCommentKey> {
    override fun handle(command: AddFindingComment): QualityFindingCommentKey {
        val author = currentUser()
        val key = QualityFindingCommentKey.generate()

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO quality_finding_comments (tenant_key, finding_id, id, body, author_id, author_name, created_at)
                SELECT :tenantKey, :findingKey, :id, :body, :authorId, :authorName, :now
                -- Guarded rather than blind: the composite FK would reject a bad finding_id anyway,
                -- but as an opaque constraint violation rather than something a caller can act on.
                WHERE EXISTS (SELECT 1 FROM quality_findings f WHERE f.tenant_key = :tenantKey AND f.id = :findingKey)
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("findingKey", command.findingKey.value)
                .bind("id", key.value)
                .bind("body", command.body)
                .bind("authorId", author.userId.value)
                // Denormalized so the comment still renders after the user is deleted (author_id
                // then nulls out). Mirrors feedback_comments.
                .bind("authorName", author.displayName)
                .bind("now", EpistolaClock.instant())
                .execute()
                .also {
                    require(it > 0) { "No quality finding ${command.findingKey} in tenant ${command.tenantKey}" }
                }
        }

        return key
    }
}
