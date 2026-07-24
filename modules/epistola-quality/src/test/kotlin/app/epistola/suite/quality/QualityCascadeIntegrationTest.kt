// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.AddFindingComment
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A finding is metadata *about* a resource and means nothing once that resource is gone.
 *
 * The cleanup is the database's job, via foreign keys pointing quality -> core — the same direction
 * the module dependency already points. That is what lets `DeleteDocumentTemplate` stay entirely
 * ignorant of quality, which is the invariant keeping this module droppable. These tests exist
 * because that arrangement is invisible from either side: nothing in core mentions quality, and
 * nothing in quality subscribes to a delete.
 */
class QualityCascadeIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var jdbi: Jdbi

    private val source = QualitySourceId("example")

    private fun finding(fingerprint: String = "fp-1") = SubmittedFinding(
        ruleId = "example.rule",
        severity = QualitySeverity.WARNING,
        fingerprint = fingerprint,
        message = "something is off",
    )

    private fun countIn(
        table: String,
        tenantKey: app.epistola.suite.common.ids.TenantKey,
    ): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT COUNT(*)::int FROM $table WHERE tenant_key = :t")
            .bind("t", tenantKey)
            .mapTo(Int::class.java)
            .one()
    }

    private fun newTemplate(tenantKey: app.epistola.suite.common.ids.TenantKey, name: String = "Invoice"): TemplateId {
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenantKey)))
        withMediator { CreateDocumentTemplate(id = templateId, name = name).execute() }
        return templateId
    }

    private fun newVariant(templateId: TemplateId): VariantId {
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        withMediator { CreateVariant(id = variantId, title = "Variant", description = null).execute() }
        return variantId
    }

    @Test
    fun `deleting a template takes its findings, ignores and comments with it`() {
        val tenant = createTenant("Cascade Template")
        val templateId = newTemplate(tenant.id)
        val variantId = newVariant(templateId)
        val subject = QualitySubject.of(variantId)

        withMediator { SubmitQualityFindings(source, subject, listOf(finding())).execute() }
        val key = withMediator { ListQualityFindings(tenant.id).query().items.single().key }
        withMediator {
            AddFindingComment(tenant.id, key, "worth discussing").execute()
            IgnoreFinding(tenant.id, key, "not applicable").execute()
        }
        assertThat(countIn("quality_findings", tenant.id)).isEqualTo(1)

        withMediator { DeleteDocumentTemplate(templateId).execute() }

        assertThat(countIn("quality_findings", tenant.id)).isZero()
        assertThat(countIn("quality_finding_comments", tenant.id)).isZero()
        assertThat(countIn("quality_finding_ignores", tenant.id)).isZero()
    }

    @Test
    fun `deleting one variant leaves the template's other findings alone`() {
        val tenant = createTenant("Cascade Variant")
        val templateId = newTemplate(tenant.id)
        val doomed = newVariant(templateId)
        val survivor = newVariant(templateId)

        withMediator {
            SubmitQualityFindings(source, QualitySubject.of(doomed), listOf(finding("fp-doomed"))).execute()
            SubmitQualityFindings(source, QualitySubject.of(survivor), listOf(finding("fp-survivor"))).execute()
        }
        assertThat(countIn("quality_findings", tenant.id)).isEqualTo(2)

        withMediator { DeleteVariant(doomed).execute() }

        val remaining = withMediator { ListQualityFindings(tenant.id).query().items }
        assertThat(remaining).singleElement().satisfies({ assertThat(it.fingerprint).isEqualTo("fp-survivor") })
    }

    /**
     * A template-subject finding has a NULL `variant_key`, so the variant FK must not apply to it —
     * under MATCH SIMPLE it is skipped. Deleting some *other* variant must leave it standing.
     */
    @Test
    fun `a template-level finding survives a variant deletion`() {
        val tenant = createTenant("Cascade Template Level")
        val templateId = newTemplate(tenant.id)
        val variantId = newVariant(templateId)

        withMediator {
            SubmitQualityFindings(source, QualitySubject.of(templateId), listOf(finding("fp-template"))).execute()
        }

        withMediator { DeleteVariant(variantId).execute() }

        assertThat(withMediator { ListQualityFindings(tenant.id).query().items })
            .singleElement()
            .satisfies({ assertThat(it.fingerprint).isEqualTo("fp-template") })
    }

    /**
     * The failure that makes this worth a foreign key rather than a cleanup job.
     *
     * URNs are built from slugs, so deleting a template and creating another with the same key
     * reproduces its URN exactly — and an ignore keyed on that URN would silently suppress findings
     * on a template nobody has ever reviewed. Cascading the ignore away is what stops that.
     */
    @Test
    fun `an ignore does not survive to suppress findings on a re-created template`() {
        val tenant = createTenant("Cascade Resurrection")
        val templateId = newTemplate(tenant.id)
        val variantId = newVariant(templateId)
        val subject = QualitySubject.of(variantId)

        withMediator { SubmitQualityFindings(source, subject, listOf(finding())).execute() }
        val key = withMediator { ListQualityFindings(tenant.id).query().items.single().key }
        withMediator { IgnoreFinding(tenant.id, key, "reviewed on the OLD template").execute() }

        withMediator { DeleteDocumentTemplate(templateId).execute() }

        // Same keys, so the same URNs and the same fingerprint — a fresh template as far as anyone
        // reviewing is concerned.
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            CreateVariant(id = variantId, title = "Variant", description = null).execute()
            SubmitQualityFindings(source, subject, listOf(finding())).execute()
        }

        val resurfaced = withMediator { ListQualityFindings(tenant.id).query().items.single() }
        assertThat(resurfaced.effectiveStatus)
            .describedAs("a stale ignore must not suppress a finding on a template nobody has reviewed")
            .isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(resurfaced.ignoreReason).isNull()
    }

    @Test
    fun `deleting the tenant takes everything with it`() {
        val tenant = createTenant("Cascade Tenant")
        val templateId = newTemplate(tenant.id)
        val subject = QualitySubject.of(newVariant(templateId))
        withMediator { SubmitQualityFindings(source, subject, listOf(finding())).execute() }

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM tenants WHERE id = :t").bind("t", tenant.id).execute()
        }

        assertThat(countIn("quality_findings", tenant.id)).isZero()
    }
}
