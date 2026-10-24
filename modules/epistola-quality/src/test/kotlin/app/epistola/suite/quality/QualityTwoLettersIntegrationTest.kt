// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTenantCounter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Two catalogs called `letters` (ADR 0034, section 9), each with a template `invoice`: their findings
 * are two subjects, named by catalog address, so an ignore of one is not an ignore of the other and a
 * list of one catalog's findings holds that catalog's only.
 */
class QualityTwoLettersIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")
    private val letters = CatalogKey.of("letters")

    private fun finding() = SubmittedFinding(ruleId = "example.rule", severity = QualitySeverity.WARNING, fingerprint = "fp-1", message = "something is off")

    private fun subjects(): Pair<QualitySubject, QualitySubject> = withMediator {
        val tenant = CreateTenant(
            TenantKey.of("qualitytwo-${TestTenantCounter.next("qualitytwo")}"),
            "Quality two letters",
            listOf(NamespaceName("gemeente-x.nl", "acme"), NamespaceName("gemeente-x.nl", "bureau")),
        ).execute().id
        val (acme, bureau) = listOf("acme", "bureau").map { namespace ->
            CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
            val template = TemplateId(TemplateKey.of("invoice"), CatalogId(namespace, letters, TenantId(tenant)))
            CreateDocumentTemplate(template, "Invoice of $namespace").execute()
            QualitySubject.of(VariantId(VariantKey.INITIAL, template))
        }
        acme to bureau
    }

    private fun findingsOf(subject: QualitySubject) = withMediator {
        GetFindingsForSubject(subject.catalogId, subject.templateKey, subject.variantKey!!).query().findings
    }

    @Test
    fun `an ignore of one letters' finding leaves the other's open, and each is listed in its own catalog`() {
        val (acme, bureau) = subjects()
        assertThat(acme.ignoreScopeUrn).isNotEqualTo(bureau.ignoreScopeUrn).contains("/acme/letters/")

        withMediator {
            SubmitQualityFindings(source, acme, listOf(finding())).execute()
            SubmitQualityFindings(source, bureau, listOf(finding())).execute()
            IgnoreFinding(acme.tenantKey, findingsOf(acme).single().key, "Intentional").execute()
        }

        assertThat(findingsOf(acme).single().effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(findingsOf(bureau).single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        val listed = withMediator { ListQualityFindings(acme.tenantKey, catalog = bureau.catalogId).query() }
        assertThat(listed.items.map { it.catalogAddress }).containsExactly("bureau/letters")
    }
}
