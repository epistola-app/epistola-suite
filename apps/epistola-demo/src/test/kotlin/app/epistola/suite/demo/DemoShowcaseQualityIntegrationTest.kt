// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.commands.SourceRunStatus
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.quality.sources.AccessibilityQualitySource
import app.epistola.suite.quality.sources.ExampleQualitySource
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The demo catalog's `quality-showcase` really does demonstrate the feature.
 *
 * Every feature must be exercised in the demo catalog (CLAUDE.md item 13), and for this one that
 * claim is checkable rather than a matter of opinion: install the bundled catalog, run the real
 * sources through the real command, and assert the findings a reader will actually see. It fails if
 * someone "tidies" the deliberately flawed template — which would leave the demo, the report page
 * and the editor panel with nothing to show and no signal that they had.
 */
class DemoShowcaseQualityIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    /** Installs the bundled demo catalog and returns the showcase variant. */
    private fun showcaseVariant(): VariantId {
        val tenant = createTenant("Demo Showcase")
        return withMediator {
            EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute()
            VariantId(
                VariantKey.of("default"),
                TemplateId(
                    TemplateKey.of("quality-showcase"),
                    testCatalog(TenantId(tenant.id), CatalogKey.of("epistola-demo")),
                ),
            )
        }
    }

    @Test
    fun `the showcase template reports one empty block and one overlong block`() {
        val variantId = showcaseVariant()

        withMediator { RunQualityChecks(variantId).execute() }

        val findings = withMediator { ListQualityFindings(variantId.tenantKey).query().items }

        assertThat(findings)
            .describedAs("the showcase exists to produce findings; if it produces none, the demo shows nothing")
            .isNotEmpty

        val empty = findings.single { it.ruleId == ExampleQualitySource.RULE_EMPTY_TEXT }
        assertThat(empty.severity).isEqualTo(QualitySeverity.WARNING)
        assertThat(empty.nodeIds).containsExactly("n-empty")

        val long = findings.single { it.ruleId == ExampleQualitySource.RULE_LONG_TEXT }
        assertThat(long.severity).isEqualTo(QualitySeverity.INFO)
        assertThat(long.nodeIds).containsExactly("n-long")
        // The source attaches its evidence; the detail page renders it verbatim.
        assertThat(long.context.get("length").asInt()).isGreaterThan(ExampleQualitySource.LONG_TEXT_THRESHOLD)
        // The i18n hook, demonstrated: a stable code plus the param carried as data, so this
        // finding could be re-rendered in another locale from `code + context.length` alone.
        assertThat(long.messageCode).isEqualTo(ExampleQualitySource.MSG_LONG_TEXT)
    }

    /**
     * The showcase carries an image with no alt *and* a decorative one, so the demo shows both
     * halves of the rule: the real accessibility defect, and the case the source is right to stay
     * quiet about.
     */
    @Test
    fun `the showcase reports its unlabelled image and stays quiet about the decorative one`() {
        val variantId = showcaseVariant()

        withMediator { RunQualityChecks(variantId).execute() }

        val findings = withMediator { ListQualityFindings(variantId.tenantKey).query().items }

        val alt = findings.single { it.ruleId == AccessibilityQualitySource.RULE_IMAGE_MISSING_ALT }
        assertThat(alt.severity).isEqualTo(QualitySeverity.WARNING)
        assertThat(alt.nodeIds).containsExactly("n-unlabelled-image")
        // Marking an image decorative is a real answer, not a way of dodging the check.
        assertThat(findings.flatMap { it.nodeIds }).doesNotContain("n-decorative-rule")
    }

    /**
     * `n-dynamic-only` renders nothing but an expression, and must not be reported empty — the
     * false positive that would otherwise greet a reader on the demo's own templates.
     */
    @Test
    fun `a block holding only an expression is not among the findings`() {
        val variantId = showcaseVariant()

        withMediator { RunQualityChecks(variantId).execute() }

        val findings = withMediator { ListQualityFindings(variantId.tenantKey).query().items }
        assertThat(findings.flatMap { it.nodeIds }).doesNotContain("n-dynamic-only")
    }

    /**
     * The language checks (ADR 0044): `n-language` is written to trip the built-in writing rules, and its
     * misspelling ("recieve") is left for a LanguageTool server, which the demo does not run by default.
     */
    @Test
    fun `the showcase's language paragraph trips the built-in rules, with the flagged text and suggestions`() {
        val variantId = showcaseVariant()

        val run = withMediator { RunQualityChecks(variantId).execute() }

        val language = withMediator { ListQualityFindings(variantId.tenantKey).query().items }
            .filter { it.sourceId == QualitySourceId("builtin") && it.nodeIds == listOf("n-language") }
        assertThat(language.map { it.ruleId to it.context.path("quote").asString() }).containsExactlyInAnyOrder(
            "clear-language.repeated-word" to "the the",
            "clear-language.plain-words" to "Utilise",
            "clear-language.plain-words" to "prior to",
        )
        assertThat(language.single { it.ruleId == "clear-language.repeated-word" }.context.path("suggestions").toList().map { it.asString() }).containsExactly("the")
        // The legalese the example source already finds too long reads as a writing problem as well.
        val legalese = withMediator { ListQualityFindings(variantId.tenantKey).query().items }
            .filter { it.sourceId == QualitySourceId("builtin") && it.nodeIds == listOf("n-long") }
        assertThat(legalese.map { it.ruleId }).containsExactlyInAnyOrder("clear-language.sentence-length", "clear-language.plain-words", "clear-language.plain-words")
        // Nobody configured a LanguageTool server for the demo: it says so rather than reporting nothing.
        assertThat(run.sources.single { it.sourceId == QualitySourceId("languagetool") }.status).isEqualTo(SourceRunStatus.SKIPPED)
    }

    /** The whole point of reconciliation, on the resource a reader can actually open. */
    @Test
    fun `re-running the checks is idempotent`() {
        val variantId = showcaseVariant()

        val first = withMediator { RunQualityChecks(variantId).execute() }
        val second = withMediator { RunQualityChecks(variantId).execute() }

        val source = QualitySourceId("example")
        assertThat(first[source]!!.opened).isEqualTo(2)
        assertThat(second[source]!!.opened).isZero()
        assertThat(second[source]!!.resolved).isZero()
        assertThat(second[source]!!.unchanged).isEqualTo(2)
        // The example source's counts are per-source and so unchanged; the tenant-wide total also
        // carries the accessibility source's unlabelled-image finding and the built-in rules' six
        // (three in n-language, three in the legalese of n-long).
        assertThat(withMediator { ListQualityFindings(variantId.tenantKey).query().total }).isEqualTo(9)
        assertThat(second[QualitySourceId("builtin")]!!.unchanged).isEqualTo(6)
    }
}
