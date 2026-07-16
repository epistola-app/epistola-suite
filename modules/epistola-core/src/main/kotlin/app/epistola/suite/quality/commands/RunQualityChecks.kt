package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.SubmitFindingsResult
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Runs the **in-process** sources against a variant and submits what they find.
 *
 * Drives all three local triggers: the scheduled sweep, the after-publish re-check, and the editor's
 * "Check now". Remote sources are untouched by this — they push on their own schedule.
 *
 * Reads the **persisted** draft (falling back to the newest published version), so an editor calling
 * this must flush its save first; checking unsaved state would be stale-in, stale-out.
 *
 * One source throwing does not sink the others: its exception is logged and its findings are left
 * exactly as they were, rather than a half-run being reconciled as "this source now reports
 * nothing" — which would resolve every one of its open findings on the strength of a bug.
 */
data class RunQualityChecks(
    val variantId: VariantId,
    /** Null runs every available source; a subset is used by "Check now" for one panel. */
    val sourceIds: Set<QualitySourceId>? = null,
) : Command<Map<QualitySourceId, SubmitFindingsResult>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class RunQualityChecksHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val registry: QualitySourceRegistry,
) : CommandHandler<RunQualityChecks, Map<QualitySourceId, SubmitFindingsResult>> {
    override fun handle(command: RunQualityChecks): Map<QualitySourceId, SubmitFindingsResult> {
        val subject = QualitySubject.of(command.variantId)

        val sources = registry.availableFor(subject.tenantKey)
            .filter { command.sourceIds == null || it.sourceId in command.sourceIds }
        if (sources.isEmpty()) return emptyMap()

        val input = loadCheckInput(subject) ?: return emptyMap()

        return sources.mapNotNull { source ->
            val findings = try {
                source.check(input)
            } catch (e: Exception) {
                // Deliberately not rethrown: a broken source must not resolve its own findings by
                // appearing to report nothing, nor take down the sources that work.
                log.error(
                    "Quality source {} failed for {} — leaving its findings untouched",
                    source.sourceId,
                    subject.urn,
                    e,
                )
                return@mapNotNull null
            }
            source.sourceId to SubmitQualityFindings(source.sourceId, subject, findings).execute()
        }.toMap()
    }

    /**
     * The variant's current document and its template's example data, in one round trip.
     *
     * Draft first, else newest published — the same resolution `SubmitQualityFindings` and the
     * editor use, so a source analyses exactly the document the author is looking at. The contract
     * is resolved draft-first too, since example data is what checks are allowed to see.
     *
     * Null when the variant has no document at all, in which case there is nothing to check.
     */
    private fun loadCheckInput(subject: QualitySubject): QualityCheckInput? = jdbi.withHandle<QualityCheckInput?, Exception> { handle ->
        val templateModel = handle.createQuery(
            """
            SELECT template_model
            FROM template_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
              AND template_key = :templateKey AND variant_key = :variantKey
              AND status IN ('draft', 'published')
            ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", subject.tenantKey)
            .bind("catalogKey", subject.catalogKey)
            .bind("templateKey", subject.templateKey)
            .bind("variantKey", subject.variantKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
            ?.let { objectMapper.readValue(it, TemplateDocument::class.java) }
            ?: return@withHandle null

        val contract = handle.createQuery(
            """
            SELECT data_model, data_examples
            FROM contract_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND template_key = :templateKey
              AND status IN ('draft', 'published')
            ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", subject.tenantKey)
            .bind("catalogKey", subject.catalogKey)
            .bind("templateKey", subject.templateKey)
            .map { rs, _ -> rs.getString("data_model") to rs.getString("data_examples") }
            .findOne()
            .orElse(null)

        QualityCheckInput(
            subject = subject,
            templateModel = templateModel,
            dataExamples = contract?.second
                ?.let { objectMapper.readValue(it, Array<DataExample>::class.java).toList() }
                ?: emptyList(),
            dataModel = contract?.first?.let { objectMapper.readValue(it, ObjectNode::class.java) },
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RunQualityChecksHandler::class.java)
    }
}
