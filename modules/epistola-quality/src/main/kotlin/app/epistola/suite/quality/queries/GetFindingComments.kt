// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.QualityFindingCommentKey
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

/** A comment on a finding. [authorId] is null once the author's user record is gone; [authorName] survives. */
data class QualityFindingComment(
    val key: QualityFindingCommentKey,
    val body: String,
    val authorId: UserKey?,
    val authorName: String,
    val createdAt: Instant,
)

/**
 * The discussion on the problem [findingKey] reports, oldest first: comments belong to the problem
 * (scope, source, rule, fingerprint), as an ignore does, so this is the same discussion in every
 * version and through every check that reports it (ADR 0044).
 */
data class GetFindingComments(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
) : Query<List<QualityFindingComment>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetFindingCommentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFindingComments, List<QualityFindingComment>> {
    override fun handle(query: GetFindingComments): List<QualityFindingComment> = jdbi.withHandle<List<QualityFindingComment>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.id, c.body, c.author_id, c.author_name, c.created_at
            FROM quality_findings f
            JOIN quality_finding_comments c
              ON c.tenant_key = f.tenant_key AND c.ignore_scope_urn = f.ignore_scope_urn AND c.source_id = f.source_id
             AND c.rule_id = f.rule_id AND c.finding_fingerprint = f.fingerprint
            WHERE f.tenant_key = :tenantKey AND f.id = :findingKey
            ORDER BY c.created_at ASC, c.id ASC
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("findingKey", query.findingKey.value)
            .map { rs, _ ->
                QualityFindingComment(
                    key = QualityFindingCommentKey.of(rs.getObject("id", java.util.UUID::class.java)),
                    body = rs.getString("body"),
                    authorId = rs.getObject("author_id", java.util.UUID::class.java)?.let { UserKey.of(it) },
                    authorName = rs.getString("author_name"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }
            .list()
    }
}
