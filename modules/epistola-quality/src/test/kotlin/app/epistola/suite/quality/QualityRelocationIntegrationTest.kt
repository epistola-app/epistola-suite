// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A finding's `subject_urn` and an ignore's `ignore_scope_urn` are built from the subject's
 * *address*, and the ignore URN is the join condition between the two. Relocating a template
 * therefore has to carry them along, or the join stops matching and every ignored finding
 * reappears as open — silently discarding an author's triage.
 */
class QualityRelocationIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")

    @Test
    fun `an ignored finding stays ignored after its template moves catalogs`() {
        val tenant = createTenant("Quality relocation")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId(sourceCatalog, tenantId))

        val subject = withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            QualitySubject.of(variantId)
        }

        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(
                    SubmittedFinding(
                        ruleId = "example.rule",
                        severity = QualitySeverity.WARNING,
                        fingerprint = "fp-1",
                        message = "something is off",
                    ),
                ),
            ).execute()
        }
        val open = withMediator { findingsFor(subject) }.findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "Intentional — legal requires this wording").execute() }
        assertThat(withMediator { findingsFor(subject) }.findings.single().effectiveStatus)
            .isEqualTo(EffectiveQualityStatus.IGNORED)

        val address = ResourceAddress(CatalogResourceType.TEMPLATE, sourceCatalog.value, templateId.key.value)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The source submits again, as it does on every sweep. Rebuilding the subject at the new
        // address recomputes its URNs -- which is precisely what breaks if the ignore was left
        // holding the old one. Reading back without re-submitting proves nothing: the finding and
        // its ignore would both still be stale, and still match each other.
        val movedSubject = QualitySubject.of(
            VariantId(
                VariantKey.of(subject.variantKey!!),
                TemplateId(templateId.key, CatalogId(targetCatalog, tenantId)),
            ),
        )
        withMediator {
            SubmitQualityFindings(
                source,
                movedSubject,
                listOf(
                    SubmittedFinding(
                        ruleId = "example.rule",
                        severity = QualitySeverity.WARNING,
                        fingerprint = "fp-1",
                        message = "something is off",
                    ),
                ),
            ).execute()
        }

        val after = withMediator { findingsFor(movedSubject) }.findings.single()
        assertThat(after.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
    }

    private fun findingsFor(subject: QualitySubject) = GetFindingsForSubject(
        tenantKey = subject.tenantKey,
        catalogKey = subject.catalogKey,
        templateKey = subject.templateKey,
        variantKey = subject.variantKey!!,
    ).query()
}
