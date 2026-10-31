// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.commands.SaveQualityProviderSettings
import app.epistola.suite.quality.commands.SourceRunStatus
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.queries.ListQualityProviders
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.doc
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.document
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.paragraph
import app.epistola.suite.quality.text.TextBlockExtractorTest.Companion.text
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * A LanguageTool that is configured but down: the brief's "fall back to a lower level and tell the
 * author" (ADR 0044). The built-in rules still run, LanguageTool's earlier findings stay as they were
 * rather than resolving on the strength of an outage, and the run says it failed.
 */
@TestPropertySource(properties = ["epistola.quality.language-tool.url=http://127.0.0.1:1"])
class LanguageToolUnavailableIntegrationTest : IntegrationTestBase() {
    /** A tenant administrator switches it on and off; the built-in rules are not switchable. */
    @Test
    fun `a configured LanguageTool is switched on and off in the tenant's quality settings`() {
        val tenant = createTenant("LT Settings")
        val languageTool = QualitySourceId("languagetool")
        fun providers() = withMediator { ListQualityProviders(tenant.id).query() }

        assertThat(providers().providers.single { it.id == languageTool }).satisfies({
            assertThat(it.canSwitch).isTrue()
            assertThat(it.enabled).isFalse()
        })
        assertThat(providers().providers.single { it.id == QualitySourceId("builtin") }).satisfies({
            assertThat(it.canSwitch).isFalse()
            assertThat(it.enabled).isTrue()
        })

        withMediator { SaveQualityProviderSettings(tenant.id, enabled = setOf(languageTool)).execute() }
        assertThat(providers().providers.single { it.id == languageTool }.enabled).isTrue()

        withMediator { SaveQualityProviderSettings(tenant.id, enabled = emptySet()).execute() }
        assertThat(providers().providers.single { it.id == languageTool }.enabled).isFalse()
    }

    @Test
    fun `only a switchable provider can be named`() {
        val tenant = createTenant("LT Invalid")

        assertThatThrownBy { withMediator { SaveQualityProviderSettings(tenant.id, enabled = setOf(QualitySourceId("builtin"))).execute() } }
            .isInstanceOf(ValidationException::class.java)
    }

    /** The operator configured a server, but text goes to it only for a tenant that switched it on (ADR 0047). */
    @Test
    fun `a tenant that did not switch LanguageTool on is skipped, and nothing is sent`() {
        val tenant = createTenant("LT Off")
        val variantId = withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Brief").execute()
            VariantId(TestIdHelpers.nextVariantId(), templateId).also { CreateVariant(id = it, title = "Standaard", description = null).execute() }
        }

        val run = withMediator { RunQualityChecks(variantId).execute() }

        val languageTool = run.sources.single { it.sourceId == QualitySourceId("languagetool") }
        assertThat(languageTool.status).isEqualTo(SourceRunStatus.SKIPPED)
        assertThat(languageTool.reason).contains("not switched on in this tenant's quality settings")
    }

    @Test
    fun `an unreachable LanguageTool fails without resolving its findings, and the writing rules still run`() {
        val tenant = createTenant("LT Down")
        val variantId = withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
            SaveQualityProviderSettings(tenant.id, enabled = setOf(QualitySourceId("languagetool"))).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Brief").execute()
            VariantId(TestIdHelpers.nextVariantId(), templateId).also {
                CreateVariant(id = it, title = "Standaard", description = null).execute()
                UpdateVariantContent(it, document("t" to doc(paragraph(text("Wij sturen u de de stukken middels deze brief."))))).execute()
            }
        }
        val languageTool = QualitySourceId("languagetool")
        // What an earlier run, while the server was up, had found.
        withMediator {
            SubmitQualityFindings(
                languageTool,
                QualitySubject.of(variantId),
                listOf(SubmittedFinding(ruleId = "MORFOLOGIK_RULE_NL_NL", severity = QualitySeverity.WARNING, fingerprint = "earlier", message = "Spelfout", nodeIds = listOf("t"))),
            ).execute()
        }

        val run = withMediator { RunQualityChecks(variantId).execute() }

        assertThat(run.sources.single { it.sourceId == languageTool }.status).isEqualTo(SourceRunStatus.FAILED)
        assertThat(run.sources.single { it.sourceId == QualitySourceId("builtin") }.status).isEqualTo(SourceRunStatus.CHECKED)

        val findings = withMediator {
            GetFindingsForSubject(catalog = testCatalog(variantId.tenantKey, variantId.catalogKey), templateKey = variantId.templateKey, variantKey = variantId.key.value).query().findings
        }
        assertThat(findings.single { it.sourceId == languageTool }.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(findings.filter { it.sourceId == QualitySourceId("builtin") }.map { it.ruleId })
            .containsExactlyInAnyOrder("clear-language.repeated-word", "clear-language.plain-words")
    }
}
