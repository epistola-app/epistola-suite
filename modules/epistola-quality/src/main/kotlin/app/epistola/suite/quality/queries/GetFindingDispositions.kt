package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.FindingDisposition
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant

/** One page of dispositions plus the total matching the same filters, from one `COUNT(*) OVER()`. */
data class FindingDispositionPage(
    val items: List<FindingDisposition>,
    val total: Int,
)

/**
 * What humans have decided about this source's findings — the feedback leg of the ledger.
 *
 * A source polls this as part of its own cycle (the same trip on which it submits), so it can stop
 * re-reporting what a person has already dismissed, or reinstate something whose ignore was lifted.
 * Findings flow in; dispositions flow back out. Nothing is pushed at a source: it asks when it is
 * ready, which keeps a person clicking "ignore" from triggering an outbound network call.
 *
 * ### The cursor
 *
 * [since] is compared against `updated_at`, which the shared `set_updated_at()` trigger bumps on
 * **both** ignore and revoke — so both are observable events on one monotonic cursor, and a source
 * that polls `since = <last seen>` cannot miss either.
 *
 * This is exactly why an unignore soft-deletes. A hard `DELETE` would leave nothing for the cursor
 * to return, so a source would never learn the ignore was lifted and would suppress the finding
 * forever — or would have to re-scan every ignore each cycle, defeating the cursor. [FindingDisposition.ignored]
 * is therefore false for a revoked ignore, rather than the row being absent.
 */
data class GetFindingDispositions(
    override val tenantKey: TenantKey,
    val sourceId: QualitySourceId,
    val since: Instant? = null,
    val limit: Int = 200,
    val offset: Int = 0,
) : Query<FindingDispositionPage>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetFindingDispositionsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFindingDispositions, FindingDispositionPage> {
    override fun handle(query: GetFindingDispositions): FindingDispositionPage = jdbi.withHandle<FindingDispositionPage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            SELECT i.finding_fingerprint, i.rule_id, i.ignore_scope_urn,
                   (i.revoked_at IS NULL) AS ignored,
                   i.reason, i.updated_at AS changed_at,
                   COUNT(*) OVER()::int AS total_count
            FROM quality_finding_ignores i
            WHERE i.tenant_key = :tenantKey AND i.source_id = :sourceId
              AND (:since::timestamptz IS NULL OR i.updated_at > :since)
            -- The tiebreaker is not decoration: a cursor ordered on a non-unique column silently
            -- drops rows that straddle a page boundary.
            ORDER BY i.updated_at ASC, i.finding_fingerprint ASC
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("sourceId", query.sourceId.value)
            .bind("since", query.since)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                DispositionRow(
                    disposition = FindingDisposition(
                        sourceId = query.sourceId,
                        ruleId = rs.getString("rule_id"),
                        fingerprint = rs.getString("finding_fingerprint"),
                        ignoreScopeUrn = rs.getString("ignore_scope_urn"),
                        ignored = rs.getBoolean("ignored"),
                        // A revoked ignore keeps its reason in the table for audit, but the feed
                        // reports it as null: the reason described why it *was* ignored, and sending
                        // it alongside ignored=false invites a source to read it as still current.
                        reason = if (rs.getBoolean("ignored")) rs.getString("reason") else null,
                        changedAt = rs.getTimestamp("changed_at").toInstant(),
                    ),
                    totalCount = rs.getInt("total_count"),
                )
            }
            .list()

        FindingDispositionPage(
            items = rows.map { it.disposition },
            // An out-of-range page returns no rows, so there is no window value to read.
            total = rows.firstOrNull()?.totalCount ?: 0,
        )
    }

    private data class DispositionRow(
        val disposition: FindingDisposition,
        val totalCount: Int,
    )
}
