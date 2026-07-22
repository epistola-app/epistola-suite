package app.epistola.suite.quality.commands

import app.epistola.catalog.protocol.FontRef
import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityDataRequirement
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.ResolvedTemplateDependencies
import app.epistola.suite.quality.SubmitFindingsResult
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.queries.GetEditorContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs the **in-process** sources against a variant and submits what they find.
 *
 * Drives the local triggers: the after-publish re-check and the editor's "Check now". Remote
 * sources are untouched by this — they push on their own schedule.
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
    private val registry: QualitySourceRegistry,
) : CommandHandler<RunQualityChecks, Map<QualitySourceId, SubmitFindingsResult>> {
    override fun handle(command: RunQualityChecks): Map<QualitySourceId, SubmitFindingsResult> {
        val subject = QualitySubject.of(command.variantId)

        val sources = registry.availableFor(subject.tenantKey)
            .filter { command.sourceIds == null || it.sourceId in command.sourceIds }
        if (sources.isEmpty()) return emptyMap()

        val requirements = sources.flatMapTo(mutableSetOf()) { it.requirements }
        val input = loadCheckInput(command.variantId, subject, requirements) ?: return emptyMap()

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
     * The variant's current document and its template's example data.
     *
     * Reads through core's [GetEditorContext] rather than querying `template_versions` /
     * `contract_versions` directly: it already resolves exactly what a check needs (draft first,
     * else newest published) in one round trip, and going through the query means this module
     * depends on core's *contract* rather than its schema — so a core migration can't silently
     * break check execution. It is also, literally, the same context the editor renders from, which is
     * what "a source analyses the document the author is looking at" should mean.
     *
     * Null when the variant has no document at all — nothing to check.
     */
    private fun loadCheckInput(
        variantId: VariantId,
        subject: QualitySubject,
        requirements: Set<QualityDataRequirement>,
    ): QualityCheckInput? {
        val context = GetEditorContext(variantId).query() ?: return null
        return QualityCheckInput(
            subject = subject,
            templateModel = context.templateModel,
            dataExamples = context.dataExamples,
            dataModel = context.dataModel,
            dependencies = resolveDependencies(subject, context.templateModel, requirements),
        )
    }

    private fun resolveDependencies(
        subject: QualitySubject,
        templateModel: app.epistola.suite.templates.model.TemplateDocument,
        requirements: Set<QualityDataRequirement>,
    ): ResolvedTemplateDependencies {
        if (QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES !in requirements) {
            return ResolvedTemplateDependencies.EMPTY
        }
        return ResolvedTemplateDependencies(fonts = resolveFonts(subject, templateModel))
    }

    /**
     * Resolves every font the template references to a "does it exist" boolean, so a pure source can
     * flag the ones that do not without touching the database itself.
     *
     * Distinct refs only — a font used on twenty nodes is one lookup. Each is resolved against its
     * own catalog (a ref with no catalogKey lives in the template's catalog), at a neutral
     * weight/style: `ResolveFontFace` picks the nearest available face, so a null result means the
     * family ships **no** face at all, which is exactly "unresolved". A slug that is not even a valid
     * font key resolves to false rather than throwing — a malformed style is still a real problem to
     * report, not one to crash check execution over.
     */
    private fun resolveFonts(
        subject: QualitySubject,
        templateModel: app.epistola.suite.templates.model.TemplateDocument,
    ): Map<FontRef, Boolean> = DependencyScanner.documentFontRefs(templateModel).associateWith { ref ->
        val slug = FontKey.validateOrNull(ref.slug) ?: return@associateWith false
        val catalog = ref.catalogKey?.let { CatalogKey.validateOrNull(it) ?: return@associateWith false }
            ?: subject.catalogKey
        ResolveFontFace(
            tenantId = subject.tenantKey,
            catalogKey = catalog,
            slug = slug,
            weight = NEUTRAL_WEIGHT,
            italic = false,
        ).query() != null
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RunQualityChecksHandler::class.java)

        /** Regular weight — a neutral probe; the resolver picks the nearest face, so null == none. */
        private const val NEUTRAL_WEIGHT = 400
    }
}
