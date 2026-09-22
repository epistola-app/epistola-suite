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

    /**
     * The repoint matched the moved template's URN as a substring, so a template whose key merely
     * starts with the moved one's -- `invoice` and `invoice-v2` -- could have its URNs carried off
     * to the destination too. Reading findings back cannot show it (they are found by identity);
     * the next sweep does, because it recomputes the unmoved template's URN and must still match
     * its own finding and the author's ignore.
     */
    @Test
    fun `moving a template leaves the findings of a template whose key starts the same alone`() {
        val tenant = createTenant("Quality relocation prefix")
        val tenantId = TenantId(tenant.id)
        val sourceCatalog = CatalogKey.of("letters")
        val targetCatalog = CatalogKey.of("shared")
        val invoice = TemplateId(TemplateKey.of("invoice"), CatalogId(sourceCatalog, tenantId))
        val invoiceV2 = TemplateId(TemplateKey.of("invoice-v2"), CatalogId(sourceCatalog, tenantId))

        val (subject, neighbour) = withMediator {
            CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
            CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            listOf(invoice, invoiceV2).map { templateId ->
                CreateDocumentTemplate(id = templateId, name = templateId.key.value).execute()
                val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
                CreateVariant(id = variantId, title = "Default", description = null).execute()
                QualitySubject.of(variantId)
            }
        }
        withMediator {
            for (it in listOf(subject, neighbour)) {
                SubmitQualityFindings(
                    source,
                    it,
                    listOf(SubmittedFinding(ruleId = "example.rule", severity = QualitySeverity.WARNING, fingerprint = "fp-1", message = "something is off")),
                ).execute()
            }
        }

        val neighbourFinding = withMediator { findingsFor(neighbour) }.findings.single()
        withMediator { IgnoreFinding(neighbour.tenantKey, neighbourFinding.key, "Intentional").execute() }

        val address = ResourceAddress(CatalogResourceType.TEMPLATE, sourceCatalog.value, invoice.key.value)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

        // The next sweep of the template that did not move.
        withMediator {
            SubmitQualityFindings(
                source,
                neighbour,
                listOf(SubmittedFinding(ruleId = "example.rule", severity = QualitySeverity.WARNING, fingerprint = "fp-1", message = "something is off")),
            ).execute()
        }

        val after = withMediator { findingsFor(neighbour) }.findings
        assertThat(after).describedAs("still one finding for letters/invoice-v2").hasSize(1)
        assertThat(after.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
    }

    private fun findingsFor(subject: QualitySubject) = GetFindingsForSubject(
        tenantKey = subject.tenantKey,
        catalogKey = subject.catalogKey,
        templateKey = subject.templateKey,
        variantKey = subject.variantKey!!,
    ).query()
}
