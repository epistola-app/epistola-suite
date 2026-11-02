// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.catalog.protocol.FontRef
import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityDataRequirement
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.ResolvedTemplateDependencies
import app.epistola.suite.quality.SubmitFindingsResult
import app.epistola.suite.quality.ThemePresets
import app.epistola.suite.quality.text.TextBlockExtractor
import app.epistola.suite.quality.text.TextCheckOutcome
import app.epistola.suite.quality.text.TextChecker
import app.epistola.suite.quality.text.TextQualityChecks
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.queries.EditorContext
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.tenants.queries.GetTenant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What happened to one source in a run. */
enum class SourceRunStatus {
    /** It ran and its full finding set was submitted. */
    CHECKED,

    /** It did not apply (a language checker with nothing configured, or no language chosen); its findings stay. */
    SKIPPED,

    /** It could not answer; nothing was submitted, so its earlier findings stay. */
    FAILED,
}

/** One source of a run: its status, why when it did not check, and what its submission changed. */
data class SourceRun(
    val sourceId: QualitySourceId,
    val displayName: String,
    val status: SourceRunStatus,
    val reason: String? = null,
    val result: SubmitFindingsResult? = null,
)

/** What a run did, source by source; empty when nothing ran (no document, or no source selected). */
data class QualityCheckRun(
    val sources: List<SourceRun>,
) {
    /** What [sourceId]'s submission changed, when it was checked. */
    operator fun get(sourceId: QualitySourceId): SubmitFindingsResult? = sources.firstOrNull { it.sourceId == sourceId }?.result

    companion object {
        val NOTHING = QualityCheckRun(emptyList())
    }
}

/**
 * Runs the **in-process** sources and the **language checkers** against a variant and submits what
 * they find.
 *
 * Drives the local triggers: a save (debounced, `OnVariantSavedScheduleChecks`), marking a variant
 * ready, and the editor's "Check now". Remote sources are untouched by this — they push on their own
 * schedule.
 *
 * Reads the **persisted** draft (falling back to the newest published version), so an editor calling
 * this must flush its save first; checking unsaved state would be stale-in, stale-out.
 *
 * One source throwing does not sink the others: its exception is logged and its findings are left
 * exactly as they were, rather than a half-run being reconciled as "this source now reports
 * nothing" — which would resolve every one of its open findings on the strength of a bug. A language
 * checker that is skipped or fails leaves its findings the same way (ADR 0044).
 *
 * Manages its own transactions: a language checker may be a server across the network, and a run
 * must not hold a database connection while it answers. Each submission is its own command, and so
 * its own transaction.
 */
data class RunQualityChecks(
    val variantId: VariantId,
    /** Null runs every available source; a subset is used by "Check now" for one panel. */
    val sourceIds: Set<QualitySourceId>? = null,
    /**
     * What asked for the check. A language checker runs only on the triggers its configuration names
     * (ADR 0047); several when saves and a ready mark were coalesced into one run. The in-process
     * sources are cheap and run on every trigger.
     */
    val triggers: Set<CheckTrigger> = setOf(CheckTrigger.EXPLICIT),
) : Command<QualityCheckRun>,
    RequiresPermission,
    // A language checker can be a remote server (ADR 0044); no transaction may wait on it.
    SelfManagedTransaction {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class RunQualityChecksHandler(
    private val registry: QualitySourceRegistry,
    private val references: app.epistola.suite.catalog.CatalogReferences,
    private val textChecks: TextQualityChecks,
    private val locales: TenantLocaleResolver,
) : CommandHandler<RunQualityChecks, QualityCheckRun> {
    override fun handle(command: RunQualityChecks): QualityCheckRun {
        val subject = QualitySubject.of(command.variantId)

        val sources = registry.availableFor(subject.tenantKey)
            .filter { command.sourceIds == null || it.sourceId in command.sourceIds }
        val checkers = registry.textCheckers()
            .filter { command.sourceIds == null || it.checkerId in command.sourceIds }
            .filter { checker -> checker.triggers.any { it in command.triggers } }
        if (sources.isEmpty() && checkers.isEmpty()) return QualityCheckRun.NOTHING

        // The variant's current document and its template's example data, through core's
        // GetEditorContext rather than its tables: one round trip, the very context the editor renders
        // from, and a dependency on core's contract rather than its schema. Null: nothing to check.
        val context = GetEditorContext(command.variantId).query() ?: return QualityCheckRun.NOTHING

        val sourceRuns = if (sources.isEmpty()) emptyList() else runSources(sources, checkInput(subject, context, sources))
        val textRuns = if (checkers.isEmpty()) emptyList() else runTextCheckers(subject, context, checkers)
        return QualityCheckRun(sourceRuns + textRuns)
    }

    private fun runSources(
        sources: List<QualityFindingSource>,
        input: QualityCheckInput,
    ): List<SourceRun> = sources.map { source ->
        val findings = try {
            source.check(input)
        } catch (e: Exception) {
            // Deliberately not rethrown: a broken source must not resolve its own findings by
            // appearing to report nothing, nor take down the sources that work.
            log.error(
                "Quality source {} failed for {} — leaving its findings untouched",
                source.sourceId,
                input.subject.urn,
                e,
            )
            return@map SourceRun(source.sourceId, source.displayName, SourceRunStatus.FAILED, "failed while checking")
        }
        val result = SubmitQualityFindings(source.sourceId, input.subject, findings).execute()
        SourceRun(source.sourceId, source.displayName, SourceRunStatus.CHECKED, result = result)
    }

    /** The language checkers, in the language chosen for the variant (ADR 0044). */
    private fun runTextCheckers(
        subject: QualitySubject,
        context: EditorContext,
        checkers: List<TextChecker>,
    ): List<SourceRun> {
        val tenant = GetTenant(subject.tenantKey).query() ?: return emptyList()
        val locale = locales.resolveWithSource(tenant, context.variantAttributes)
        val blocks = TextBlockExtractor.extract(context.templateModel)
        return textChecks.run(subject, blocks, locale, checkers).map { outcome ->
            val checker = outcome.checker
            when (outcome) {
                is TextCheckOutcome.Checked -> SourceRun(
                    checker.checkerId,
                    checker.displayName,
                    SourceRunStatus.CHECKED,
                    result = SubmitQualityFindings(checker.checkerId, subject, outcome.findings).execute(),
                )

                is TextCheckOutcome.Skipped -> SourceRun(checker.checkerId, checker.displayName, SourceRunStatus.SKIPPED, outcome.reason)

                is TextCheckOutcome.Failed -> SourceRun(checker.checkerId, checker.displayName, SourceRunStatus.FAILED, outcome.reason)
            }
        }
    }

    private fun checkInput(
        subject: QualitySubject,
        context: EditorContext,
        sources: List<QualityFindingSource>,
    ): QualityCheckInput {
        val requirements = sources.flatMapTo(mutableSetOf()) { it.requirements }
        return QualityCheckInput(
            subject = subject,
            templateModel = context.templateModel,
            dataExamples = context.dataExamples,
            dataModel = context.dataModel,
            dependencies = resolveDependencies(subject, context.templateModel, requirements)
                .let { if (QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES in requirements) it.copy(themePresets = themePresets(subject, context)) else it },
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
        val slug = FontKey.validateOrNull(ref.key) ?: return@associateWith false
        // The catalog the ref's alias names from the template's (ADR 0034, section 9); unresolved, none.
        if (ref.catalogKey != null && CatalogKey.validateOrNull(ref.catalogKey!!) == null) return@associateWith false
        val catalog = references.resolve(subject.catalogId, ref.catalogKey) ?: return@associateWith false
        ResolveFontFace(
            catalog = catalog,
            slug = slug,
            weight = NEUTRAL_WEIGHT,
            italic = false,
        ).query() != null
    }

    /**
     * The presets the theme the variant renders with styles: its own theme, the template's, then its
     * catalog's default (contract ADR 0007); an empty set, named by no theme, when none applies.
     */
    private fun themePresets(subject: QualitySubject, context: app.epistola.suite.templates.queries.EditorContext): ThemePresets {
        val catalogTheme = app.epistola.suite.catalog.releases.GetCatalogThemeSettings(subject.catalogId).query().defaultTheme
        val theme = app.epistola.suite.themes.queries.ResolveTemplateTheme(
            catalog = subject.catalogId,
            templateDefaultThemeId = context.templateThemeKey,
            catalogDefaultThemeId = catalogTheme?.themeKey,
            templateModel = context.templateModel,
            templateThemeCatalog = context.templateThemeCatalog,
            catalogDefaultThemeCatalog = catalogTheme?.catalog,
        ).query()
        return ThemePresets(themeName = theme?.name, styled = theme?.blockStylePresets?.keys.orEmpty())
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RunQualityChecksHandler::class.java)

        /** Regular weight — a neutral probe; the resolver picks the nearest face, so null == none. */
        private const val NEUTRAL_WEIGHT = 400
    }
}
