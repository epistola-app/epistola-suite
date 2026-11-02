// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.SubmitFindingsResult
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateIn
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Submits a source's **full current finding set** for one variant, as checked under [inputKey]
 * (ADR 0044): what the check read, from [app.epistola.suite.quality.queries.GetQualityInputKey]. The
 * findings belong to that check, and every catalog version whose input key for the variant is the
 * same shows them; a version whose key differs shows them as outdated until it is checked itself.
 *
 * This is the single entry point for every check, in-process or remote, so reconciliation and
 * decisions behave identically wherever a check ran. A person's note is [RecordManualFinding].
 *
 * ### Reconciliation, per check
 *
 * [findings] is a complete set for the check, not a delta: a fingerprint the check had not reported
 * is opened (keeping the first time the source reported it for this variant under any key), one it
 * had is refreshed and keeps its id, and one it no longer reports goes. A check's finding is never
 * stored as resolved: a problem the check shown no longer reports is simply not shown. An **empty**
 * submission is meaningful and legal: checked, nothing wrong.
 *
 * Reconciliation is scoped by source, so a source never removes another's findings, nor a person's
 * notes. Ignores and comments are keyed by the problem, not by a finding's row, so they hold through
 * every check that reports it.
 *
 * A check under a key no version of the variant has any more, by the same source, is superseded and
 * deleted with its findings: what the variant was before an edit, or before a dependency changed.
 *
 * ### Authorization
 *
 * Requires [Permission.TEMPLATE_EDIT] rather than being `SystemInternal`, so that a remote ingest
 * authorizes like any other caller. Background callers bind `SystemUser.principalForTenant(tenantKey)`.
 * `requireCatalogEditable` is deliberately **not** called: a finding is metadata *about* content.
 */
data class SubmitQualityFindings(
    val sourceId: QualitySourceId,
    val subject: QualitySubject,
    /** What the check read: the variant's input key in the version it checked. */
    val inputKey: String,
    val findings: List<SubmittedFinding>,
    /** The catalog version that was checked, in which [subject]'s template is found; null for the next. */
    override val catalogVersion: CatalogReleaseRef?,
) : Command<SubmitFindingsResult>,
    CatalogReleaseScoped,
    RequiresPermission {
    init {
        require(sourceId != QualitySourceId.MANUAL) {
            "manual findings are not reconcilable — use RecordManualFinding, which does not auto-resolve"
        }
        require(subject.variantKey != null) { "a check is of a variant (ADR 0044); ${subject.urn} is not one" }
        require(inputKey.isNotBlank()) { "a check is stored under the input key it read" }
        val duplicates = findings.groupBy { it.fingerprint }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "a submission must contain each fingerprint at most once (the ledger reconciles on it); duplicates: $duplicates"
        }
    }

    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = subject.tenantKey
}

@Component
class SubmitQualityFindingsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<SubmitQualityFindings, SubmitFindingsResult> {
    override fun handle(command: SubmitQualityFindings): SubmitFindingsResult {
        val now = EpistolaClock.instant()

        // One transaction (SpringMediator gives commands one), so a reader never sees a check half stored.
        return jdbi.inTransaction<SubmitFindingsResult, Exception> { handle ->
            val templateId = handle.createQuery("SELECT ${templateIn("tenantKey", "catalogKey", "templateKey", "v")}")
                .bind("tenantKey", command.subject.tenantKey)
                .bindCatalog("catalogKey", command.subject.catalogId)
                .bindVersion("v", handle.versionOf(command.subject.catalogId, command).versionId)
                .bind("templateKey", command.subject.templateKey)
                .mapTo(UUID::class.java)
                .findOne()
                .orElse(null)
                ?: throw IllegalArgumentException("No template ${command.subject.templateKey} in ${command.subject.catalogId.address}")
            val check = Check(command.subject.tenantKey, templateId, command.subject.variantKey!!, command.sourceId, command.inputKey)

            handle.createUpdate(
                """
                INSERT INTO quality_checks (tenant_key, template_resource_id, variant_key, source_id, input_key, checked_at)
                VALUES (:tenantKey, :templateId, :variantKey, :sourceId, :inputKey, :now)
                ON CONFLICT (tenant_key, template_resource_id, variant_key, source_id, input_key) DO UPDATE SET checked_at = EXCLUDED.checked_at
                """,
            ).bindCheck(check).bind("now", now).execute()

            val existing = handle.createQuery(
                """
                SELECT fingerprint FROM quality_findings
                WHERE tenant_key = :tenantKey AND template_resource_id = :templateId AND variant_key = :variantKey
                  AND source_id = :sourceId AND input_key = :inputKey
                """,
            ).bindCheck(check).mapTo(String::class.java).set()

            for (finding in command.findings) upsert(handle, command, check, finding, now)

            val dropped = handle.createUpdate(
                """
                DELETE FROM quality_findings
                WHERE tenant_key = :tenantKey AND template_resource_id = :templateId AND variant_key = :variantKey
                  AND source_id = :sourceId AND input_key = :inputKey AND fingerprint <> ALL(:submitted)
                """,
            ).bindCheck(check).bindArray("submitted", String::class.java, command.findings.map { it.fingerprint }).execute()

            supersede(handle, check)

            SubmitFindingsResult(
                opened = command.findings.count { it.fingerprint !in existing },
                unchanged = command.findings.count { it.fingerprint in existing },
                dropped = dropped,
            )
        }
    }

    /** One source's check of one variant under one input key: the identity of a `quality_checks` row. */
    private data class Check(val tenantKey: TenantKey, val templateId: UUID, val variantKey: String, val sourceId: QualitySourceId, val inputKey: String)

    private fun <T : org.jdbi.v3.core.statement.SqlStatement<T>> T.bindCheck(check: Check): T = bind("tenantKey", check.tenantKey)
        .bind("templateId", check.templateId)
        .bind("variantKey", check.variantKey)
        .bind("sourceId", check.sourceId.value)
        .bind("inputKey", check.inputKey)

    private fun upsert(
        handle: org.jdbi.v3.core.Handle,
        command: SubmitQualityFindings,
        check: Check,
        finding: SubmittedFinding,
        now: Instant,
    ) {
        handle.createUpdate(
            """
            INSERT INTO quality_findings (
                tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type, ignore_scope_urn,
                template_resource_id, variant_key, version_key, node_ids, path, message, message_code, docs_url,
                fingerprint, input_key, context, metadata, status, first_seen_at, last_seen_at, resolved_at
            ) VALUES (
                :tenantKey, :id, :sourceId, :ruleId, :severity, :subjectUrn, :subjectType, :ignoreScopeUrn,
                :templateId, :variantKey, :versionKey, :nodeIds, :path, :message, :messageCode, :docsUrl,
                :fingerprint, :inputKey, :context::jsonb, :metadata::jsonb, 'OPEN',
                -- When the source first reported the problem for this variant, under any key: an edit
                -- elsewhere is a new check, not a new problem.
                COALESCE((SELECT MIN(p.first_seen_at) FROM quality_findings p
                          WHERE p.tenant_key = :tenantKey AND p.template_resource_id = :templateId AND p.variant_key = :variantKey
                            AND p.source_id = :sourceId AND p.fingerprint = :fingerprint), :now),
                :now, NULL
            )
            ON CONFLICT (tenant_key, template_resource_id, variant_key, source_id, input_key, fingerprint) WHERE input_key IS NOT NULL
            DO UPDATE SET
                -- Display fields may change without the problem changing (a reworded message, a raised
                -- severity, a node added), so they take the newest submission; the id and first_seen_at stay.
                severity     = EXCLUDED.severity,
                message      = EXCLUDED.message,
                message_code = EXCLUDED.message_code,
                docs_url     = EXCLUDED.docs_url,
                node_ids     = EXCLUDED.node_ids,
                path         = EXCLUDED.path,
                context      = EXCLUDED.context,
                metadata     = EXCLUDED.metadata,
                last_seen_at = EXCLUDED.last_seen_at
            """,
        )
            .bindCheck(check)
            .bind("id", QualityFindingKey.generate().value)
            .bind("ruleId", finding.ruleId)
            .bind("severity", finding.severity.name)
            .bind("subjectUrn", command.subject.urn)
            .bind("subjectType", command.subject.type.name)
            .bind("ignoreScopeUrn", command.subject.ignoreScopeUrn)
            .bind("versionKey", command.subject.versionKey)
            .bindArray("nodeIds", String::class.java, finding.nodeIds)
            .bind("path", finding.path)
            .bind("message", finding.message)
            .bind("messageCode", finding.messageCode)
            .bind("docsUrl", finding.docsUrl)
            .bind("fingerprint", finding.fingerprint)
            .bind("context", objectMapper.writeValueAsString(finding.context))
            .bind("metadata", objectMapper.writeValueAsString(finding.metadata))
            .bind("now", now)
            .execute()
    }

    /**
     * Deletes this source's checks of the variant under a key no version has any more
     * (`quality_live_input_keys`), and their findings with them: what the variant read before an edit.
     * A release's key never changes, so its check stays as long as the release does.
     */
    private fun supersede(handle: org.jdbi.v3.core.Handle, check: Check) {
        handle.createUpdate(
            """
            DELETE FROM quality_checks
            WHERE tenant_key = :tenantKey AND template_resource_id = :templateId AND variant_key = :variantKey
              AND source_id = :sourceId AND input_key <> :inputKey
              AND input_key NOT IN (SELECT k FROM quality_live_input_keys(:tenantKey, :templateId, :variantKey) k WHERE k IS NOT NULL)
            """,
        ).bindCheck(check).execute()
    }
}
