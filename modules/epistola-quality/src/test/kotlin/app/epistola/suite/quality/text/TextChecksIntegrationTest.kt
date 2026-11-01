// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.cluster.timers.GetClusterTimer
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.i18n.LocaleSource
import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualityCheckTimer
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.commands.SourceRunStatus
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.doc
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.document
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.expression
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.paragraph
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.text
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * Language checks end to end on the real database: the result cache (ADR 0044), the built-in writing
 * rules through `RunQualityChecks`, and checking on save through the cluster timer.
 */
class TextChecksIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var textChecks: TextQualityChecks

    /** A checker that answers nothing and remembers what it was asked. */
    private class RecordingChecker(private val configuration: String = "rec/1") : TextChecker {
        val asked = mutableListOf<List<String>>()
        override val checkerId = QualitySourceId("recording")
        override val displayName = "Recording"
        override val egress = TextEgress.IN_PROCESS
        override val triggers = CheckTrigger.entries.toSet()

        override fun readiness(locale: ResolvedLocale) = TextCheckerReadiness.Ready(configuration)

        override fun check(texts: List<String>, locale: ResolvedLocale, configuration: String): List<List<TextDiagnostic>> {
            asked += texts
            return texts.map { emptyList() }
        }
    }

    private val english = ResolvedLocale("en-GB", LocaleSource.TENANT)

    private fun newVariant(tenantKey: app.epistola.suite.common.ids.TenantKey? = null, name: String = "Letter"): VariantId {
        val tenant = tenantKey ?: createTenant("Text Checks").id
        return withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant)))
            CreateDocumentTemplate(id = templateId, name = name).execute()
            VariantId(TestIdHelpers.nextVariantId(), templateId).also { CreateVariant(id = it, title = "Default", description = null).execute() }
        }
    }

    private fun save(variantId: VariantId, model: TemplateDocument) = withMediator { UpdateVariantContent(variantId, model).execute() }

    private fun findingsFor(variantId: VariantId) = withMediator {
        GetFindingsForSubject(
            catalog = testCatalog(variantId.tenantKey, variantId.catalogKey),
            templateKey = variantId.templateKey,
            variantKey = variantId.key.value,
        ).query().findings
    }

    @Test
    fun `a paragraph shared by two templates is checked once`() {
        val first = newVariant()
        val second = newVariant(first.tenantKey, "Reminder")
        val shared = paragraph(text("Thank you for your order."))
        val checker = RecordingChecker()

        withMediator {
            textChecks.run(QualitySubject.of(first), TextBlockExtractor.extract(document("t" to doc(shared))), english, listOf(checker))
            textChecks.run(QualitySubject.of(second), TextBlockExtractor.extract(document("u" to doc(shared, paragraph(text("Kind regards."))))), english, listOf(checker))
        }

        assertThat(checker.asked).containsExactly(listOf("Thank you for your order."), listOf("Kind regards."))
    }

    @Test
    fun `an edit is checked for the paragraphs it changed only, and another configuration or locale asks again`() {
        val variantId = newVariant()
        val subject = QualitySubject.of(variantId)
        val checker = RecordingChecker()
        val before = TextBlockExtractor.extract(document("t" to doc(paragraph(text("First.")), paragraph(text("Second.")))))
        val after = TextBlockExtractor.extract(document("t" to doc(paragraph(text("First.")), paragraph(text("Second, edited.")))))

        val outcome = withMediator {
            textChecks.run(subject, before, english, listOf(checker))
            textChecks.run(subject, after, english, listOf(checker)).single()
        }

        assertThat(checker.asked).containsExactly(listOf("First.", "Second."), listOf("Second, edited."))
        assertThat(outcome).isEqualTo(TextCheckOutcome.Checked(checker, emptyList(), blocks = 2, cachedBlocks = 1))

        // Another locale, or the checker under another configuration, is another key: asked again.
        withMediator { textChecks.run(subject, after, ResolvedLocale("en-US", LocaleSource.TENANT), listOf(checker)) }
        val upgraded = RecordingChecker("rec/2")
        withMediator { textChecks.run(subject, after, english, listOf(upgraded)) }

        assertThat(checker.asked.last()).containsExactly("First.", "Second, edited.")
        assertThat(upgraded.asked).containsExactly(listOf("First.", "Second, edited."))
    }

    @Test
    fun `a checker never sees an expression's source, let alone data`() {
        val variantId = newVariant()
        val checker = RecordingChecker()
        val blocks = TextBlockExtractor.extract(document("t" to doc(paragraph(text("Dear "), expression("customer.secretName"), text(", welcome.")))))

        withMediator { textChecks.run(QualitySubject.of(variantId), blocks, english, listOf(checker)) }

        assertThat(checker.asked.flatten()).containsExactly("Dear ￼, welcome.")
    }

    @Test
    fun `a check run reports the built-in rules' findings with the flagged text, suggestions and explanation, and says LanguageTool was skipped`() {
        val variantId = newVariant()
        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))

        val run = withMediator { RunQualityChecks(variantId).execute() }

        val finding = findingsFor(variantId).single { it.sourceId == QualitySourceId("builtin") }
        assertThat(finding.ruleId).isEqualTo("clear-language.repeated-word")
        assertThat(finding.severity).isEqualTo(QualitySeverity.WARNING)
        assertThat(finding.nodeIds).containsExactly("t")
        assertThat(finding.context.path("quote").asString()).isEqualTo("the the")
        assertThat(finding.context.path("suggestions").toList().map { it.asString() }).containsExactly("the")
        assertThat(finding.context.path("explanation").asString()).contains("typing mistake")

        val languageTool = run.sources.single { it.sourceId == QualitySourceId("languagetool") }
        assertThat(languageTool.status).isEqualTo(SourceRunStatus.SKIPPED)
        assertThat(languageTool.reason).contains("not configured")
        assertThat(run.sources.single { it.sourceId == QualitySourceId("builtin") }.status).isEqualTo(SourceRunStatus.CHECKED)
    }

    @Test
    fun `fixing the text clears the finding on the next run`() {
        val variantId = newVariant()
        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))
        withMediator { RunQualityChecks(variantId).execute() }

        save(variantId, document("t" to doc(paragraph(text("Please return the form.")))))
        withMediator { RunQualityChecks(variantId).execute() }

        assertThat(findingsFor(variantId).filter { it.sourceId == QualitySourceId("builtin") }).isEmpty()
    }

    @Test
    fun `a save is checked a few seconds after the last save, once`() {
        val variantId = newVariant()
        withMediator { SaveFeatureToggle(variantId.tenantKey, KnownFeatures.QUALITY, enabled = true).execute() }
        val timerKey = "${QualityCheckTimer.TYPE}:${variantId.toUrn()}@next"

        save(variantId, document("t" to doc(paragraph(text("Draft one.")))))
        scheduling.advanceTimeBy(Duration.ofSeconds(3))
        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))
        scheduling.advanceTimeBy(Duration.ofSeconds(3))

        // Six seconds after the first save, but three after the last: the second save moved the timer.
        assertThat(findingsFor(variantId)).isEmpty()
        assertThat(withMediator { GetClusterTimer(timerKey).query() }).isNotNull

        scheduling.advanceTimeBy(Duration.ofSeconds(3))

        assertThat(findingsFor(variantId).map { it.ruleId }).containsExactly("clear-language.repeated-word")
        assertThat(withMediator { GetClusterTimer(timerKey).query() }).isNull()
    }

    /** Until the pilot this trigger was an AFTER_COMMIT handler, which the mediator never invokes: a ready mark checked nothing. */
    @Test
    fun `marking a variant ready checks it at once, without waiting for the save delay`() {
        val variantId = newVariant()
        withMediator { SaveFeatureToggle(variantId.tenantKey, KnownFeatures.QUALITY, enabled = true).execute() }
        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))

        withMediator { MarkVariantReady(variantId).execute() }
        scheduling.runDue()

        assertThat(findingsFor(variantId).map { it.ruleId }).containsExactly("clear-language.repeated-word")
    }

    /** Triggers are a provider's configuration (ADR 0047): a checker not configured for a trigger does not run on it. */
    @Test
    fun `a run asks only the checkers configured for its trigger`() {
        val variantId = newVariant()
        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))

        val onSave = withMediator { RunQualityChecks(variantId, triggers = setOf(CheckTrigger.SAVE)).execute() }

        // The built-in engine runs on every trigger; LanguageTool is configured for save too, and is
        // skipped here only because no server is configured.
        assertThat(onSave.sources.map { it.sourceId.value }).contains("builtin", "languagetool")
        assertThat(withMediator { RunQualityChecks(variantId, triggers = emptySet()).execute() }.sources.map { it.sourceId.value })
            .doesNotContain("builtin", "languagetool")
    }

    @Test
    fun `a tenant without the quality feature is not checked on save`() {
        val variantId = newVariant()

        save(variantId, document("t" to doc(paragraph(text("Please return the the form.")))))
        scheduling.advanceTimeBy(Duration.ofSeconds(10))

        assertThat(withMediator { GetClusterTimer("${QualityCheckTimer.TYPE}:${variantId.toUrn()}").query() }).isNull()
        assertThat(findingsFor(variantId)).isEmpty()
    }
}
