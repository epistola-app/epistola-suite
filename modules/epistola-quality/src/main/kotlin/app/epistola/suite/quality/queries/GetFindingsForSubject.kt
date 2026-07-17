package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualityFinding
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubjectType
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.ResultSet

/**
 * The findings for one subject, plus what the subject currently hashes to — everything the editor
 * panel needs in one round trip.
 *
 * [SubjectFindings.currentInputFingerprint] is what makes the panel honest. Checks run out of band,
 * so a finding is always "as of the last check"; comparing it against each finding's stored
 * `inputFingerprint` is how the panel knows to mark one outdated rather than presenting a stale
 * claim as current.
 */
data class SubjectFindings(
    /** Hash of the template model the subject resolves to right now. Null when it has no model. */
    val currentInputFingerprint: String?,
    val findings: List<QualityFinding>,
) {
    /** True when [finding] was computed against a different document than the one live now. */
    fun isStale(finding: QualityFinding): Boolean = finding.inputFingerprint != null &&
        currentInputFingerprint != null &&
        finding.inputFingerprint != currentInputFingerprint
}

/**
 * Reads every finding recorded against a variant, whatever its source or status.
 *
 * Resolved findings are included: the panel needs them to show a finding clearing after a fix, and
 * the caller filters. Ignored ones carry their reason.
 */
data class GetFindingsForSubject(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    val variantKey: String,
) : Query<SubjectFindings>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetFindingsForSubjectHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetFindingsForSubject, SubjectFindings> {
    override fun handle(query: GetFindingsForSubject): SubjectFindings = jdbi.withHandle<SubjectFindings, Exception> { handle ->
        // Draft first, else newest published — the same document the editor is showing, and the same
        // one SubmitQualityFindings hashes, so the two fingerprints are comparable by construction.
        val currentInputFingerprint = handle.createQuery(
            """
            SELECT md5(template_model::text)
            FROM template_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
              AND template_key = :templateKey AND variant_key = :variantKey
              AND status IN ('draft', 'published')
            ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("templateKey", query.templateKey)
            .bind("variantKey", query.variantKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

        val findings = handle.createQuery(
            """
            SELECT f.*,
                   CASE WHEN f.status = 'RESOLVED'             THEN 'RESOLVED'
                        WHEN i.finding_fingerprint IS NOT NULL THEN 'IGNORED'
                        ELSE 'OPEN' END AS effective_status,
                   i.reason AS ignore_reason,
                   (f.source_id <> :manualSourceId) AS reconciled,
                   COALESCE(c.comment_count, 0)::int AS comment_count
            FROM quality_findings f
            -- The ignore is keyed on scope+fingerprint, never on the finding row, which is exactly
            -- why it survives a resolve/resurface cycle and a version publish.
            LEFT JOIN quality_finding_ignores i
                   ON i.tenant_key       = f.tenant_key
                  AND i.ignore_scope_urn = f.ignore_scope_urn
                  AND i.source_id        = f.source_id
                  AND i.rule_id          = f.rule_id
                  AND i.finding_fingerprint = f.fingerprint
                  AND i.revoked_at IS NULL
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS comment_count
                FROM quality_finding_comments qc
                WHERE qc.tenant_key = f.tenant_key AND qc.finding_id = f.id
            ) c ON TRUE
            WHERE f.tenant_key = :tenantKey AND f.catalog_key = :catalogKey
              AND f.template_key = :templateKey AND f.variant_key = :variantKey
            ORDER BY CASE f.severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                     f.last_seen_at DESC,
                     f.id
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .bind("templateKey", query.templateKey)
            .bind("variantKey", query.variantKey)
            .bind("manualSourceId", QualitySourceId.MANUAL.value)
            .map { rs, _ -> mapFinding(rs, objectMapper) }
            .list()

        SubjectFindings(currentInputFingerprint = currentInputFingerprint, findings = findings)
    }
}

/** Shared row → [QualityFinding] mapping for the quality read queries. */
internal fun mapFinding(
    rs: ResultSet,
    objectMapper: ObjectMapper,
): QualityFinding = QualityFinding(
    key = QualityFindingKey.of(rs.getObject("id", java.util.UUID::class.java)),
    sourceId = QualitySourceId(rs.getString("source_id")),
    ruleId = rs.getString("rule_id"),
    // parse, not valueOf: the column has no CHECK, and one severity this suite does not know
    // must cost that row its exact label, not the caller its whole page.
    severity = QualitySeverity.parse(rs.getString("severity")),
    subjectUrn = rs.getString("subject_urn"),
    subjectType = QualitySubjectType.valueOf(rs.getString("subject_type")),
    ignoreScopeUrn = rs.getString("ignore_scope_urn"),
    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
    templateKey = TemplateKey.of(rs.getString("template_key")),
    variantKey = rs.getString("variant_key"),
    versionKey = rs.getObject("version_key")?.let { (it as Number).toInt() },
    nodeIds = readNodeIds(rs),
    path = rs.getString("path"),
    message = rs.getString("message"),
    docsUrl = rs.getString("docs_url"),
    fingerprint = rs.getString("fingerprint"),
    inputFingerprint = rs.getString("input_fingerprint"),
    context = objectMapper.readTree(rs.getString("context")) as ObjectNode,
    effectiveStatus = EffectiveQualityStatus.valueOf(rs.getString("effective_status")),
    ignoreReason = rs.getString("ignore_reason"),
    reconciled = rs.getBoolean("reconciled"),
    commentCount = rs.getInt("comment_count"),
    firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
    lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
    resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
)

/**
 * Reads the `node_ids TEXT[]` column. Tolerates a NULL array even though the column is
 * `NOT NULL DEFAULT '{}'` — a read model that throws on an unexpected shape takes out the whole
 * report page, and "no elements" is a perfectly meaningful answer.
 */
private fun readNodeIds(rs: ResultSet): List<String> {
    val array = rs.getArray("node_ids") ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (array.array as? Array<String>)?.toList() ?: emptyList()
}
