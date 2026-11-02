// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.catalog.VERSION_ARGS
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
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
import app.epistola.suite.templates.templateIn
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** What one source's check shown for a variant was: when it ran, and whether it read what the variant reads now. */
data class SubjectCheck(
    val sourceId: QualitySourceId,
    val checkedAt: Instant,
    /** False: the variant changed since, in the version looked at; its findings are shown as outdated. */
    val current: Boolean,
)

/**
 * What the editor panel shows for one variant in one catalog version (ADR 0044): per source, the
 * findings of the check with the variant's input key there, else of its newest check, marked
 * outdated; and the notes people raised on it.
 *
 * [checks] says, per source, which check that was; a source with none has not checked the variant
 * yet. [currentInputKey] is the variant's key in the version, null when it does not hold the variant.
 */
data class SubjectFindings(
    val currentInputKey: String?,
    val findings: List<QualityFinding>,
    val checks: List<SubjectCheck> = emptyList(),
)

/**
 * Reads the findings a catalog version shows for one variant, whatever their source or status: a
 * note a person resolved is included, and the caller filters.
 */
data class GetFindingsForSubject(
    val catalog: CatalogId,
    val templateKey: TemplateKey,
    val variantKey: String,
    /** The version looked at: a draft, a release, or null for the catalog's next version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : Query<SubjectFindings>,
    ReleaseReadable,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetFindingsForSubjectHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetFindingsForSubject, SubjectFindings> {
    override fun handle(query: GetFindingsForSubject): SubjectFindings = jdbi.withHandle<SubjectFindings, Exception> { handle ->
        val templateId = handle.createQuery("SELECT ${templateIn("tenantKey", "catalogKey", "templateKey")}")
            .bind("tenantKey", query.tenantKey)
            .bindCatalog("catalogKey", query.catalog)
            .bind("templateKey", query.templateKey)
            .mapTo(UUID::class.java)
            .findOne()
            .orElse(null)
            ?: return@withHandle SubjectFindings(null, emptyList())

        val currentInputKey = handle.createQuery(
            """
            SELECT input_key FROM quality_current_variants(${VERSION_ARGS})
            WHERE tenant_key = :tenantKey AND template_resource_id = :templateId AND variant_key = :variantKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("templateId", templateId)
            .bind("variantKey", query.variantKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

        val checks = handle.createQuery(
            """
            SELECT source_id, checked_at, current FROM quality_current_checks(${VERSION_ARGS})
            WHERE tenant_key = :tenantKey AND template_resource_id = :templateId AND variant_key = :variantKey
            ORDER BY source_id
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("templateId", templateId)
            .bind("variantKey", query.variantKey)
            .map { rs, _ -> SubjectCheck(QualitySourceId(rs.getString("source_id")), rs.getTimestamp("checked_at").toInstant(), rs.getBoolean("current")) }
            .list()

        val subject = "s.tenant_key = :tenantKey AND s.template_resource_id = :templateId AND s.variant_key = :variantKey"
        val findings = handle.createQuery(
            """
            SELECT s.*, ${QualityFindingSql.columns("s")}
            FROM (${QualityFindingSql.shown(subject.replace("s.", "k."), subject.replace("s.", "f."))}) s
            ${QualityFindingSql.decisions("s")}
            ORDER BY CASE s.severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                     s.last_seen_at DESC,
                     s.id
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("templateId", templateId)
            .bind("variantKey", query.variantKey)
            .bind("manualSourceId", QualitySourceId.MANUAL.value)
            .map { rs, _ -> mapFinding(rs, objectMapper) }
            .list()

        SubjectFindings(currentInputKey = currentInputKey, findings = findings, checks = checks)
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
    catalogNamespace = rs.getString("catalog_namespace"),
    templateKey = TemplateKey.of(rs.getString("template_key")),
    variantKey = rs.getString("variant_key"),
    versionKey = rs.getObject("version_key")?.let { (it as Number).toInt() },
    nodeIds = readNodeIds(rs),
    path = rs.getString("path"),
    message = rs.getString("message"),
    messageCode = rs.getString("message_code"),
    docsUrl = rs.getString("docs_url"),
    fingerprint = rs.getString("fingerprint"),
    inputKey = rs.getString("input_key"),
    outdated = rs.getBoolean("outdated"),
    context = objectMapper.readTree(rs.getString("context")) as ObjectNode,
    metadata = objectMapper.readTree(rs.getString("metadata")) as ObjectNode,
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
