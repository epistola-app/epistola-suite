// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.AddFindingComment
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetQualityFinding
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The detail read. It derives the same `effective_status` the list does, so the two must agree —
 * a detail page that disagreed with the row a user clicked is the failure this guards.
 */
class GetQualityFindingIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")

    private fun newSubject(name: String = "Invoice"): QualitySubject {
        val tenant = createTenant("Quality Detail")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = name).execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            QualitySubject.of(variantId)
        }
    }

    private fun onlyFindingOf(subject: QualitySubject) = withMediator {
        ListQualityFindings(subject.tenantKey).query().items.single()
    }

    @Test
    fun `reads back a submitted finding with its evidence`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(
                    SubmittedFinding(
                        ruleId = "example.rule",
                        severity = QualitySeverity.ERROR,
                        fingerprint = "fp-1",
                        message = "something is off",
                        nodeIds = listOf("node-1", "node-2"),
                    ),
                ),
            ).execute()
        }
        val listed = onlyFindingOf(subject)

        val finding = withMediator { GetQualityFinding(subject.tenantKey, listed.key).query() }

        assertThat(finding).isNotNull
        assertThat(finding!!.ruleId).isEqualTo("example.rule")
        assertThat(finding.severity).isEqualTo(QualitySeverity.ERROR)
        assertThat(finding.message).isEqualTo("something is off")
        assertThat(finding.nodeIds).containsExactly("node-1", "node-2")
        assertThat(finding.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        // A reconciling source's finding auto-resolves when fixed, so the UI must not offer Resolve.
        assertThat(finding.reconciled).isTrue()
    }

    @Test
    fun `an ignored finding reads back as IGNORED with its reason`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(SubmittedFinding("example.rule", QualitySeverity.WARNING, "fp-1", "off")),
            ).execute()
        }
        val listed = onlyFindingOf(subject)
        withMediator { IgnoreFinding(subject.tenantKey, listed.key, "Intentional for this template").execute() }

        val finding = withMediator { GetQualityFinding(subject.tenantKey, listed.key).query() }

        assertThat(finding!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(finding.ignoreReason).isEqualTo("Intentional for this template")
    }

    @Test
    fun `a manual finding reads back as not reconciled and counts its comments`() {
        val subject = newSubject()
        val key = withMediator { RecordManualFinding(subject, "Tone is wrong for a reminder").execute() }
        withMediator { AddFindingComment(subject.tenantKey, key, "Agreed, softening it").execute() }

        val finding = withMediator { GetQualityFinding(subject.tenantKey, key).query() }

        // Nothing reconciles a human's note away, so the UI has to offer an explicit Resolve.
        assertThat(finding!!.reconciled).isFalse()
        assertThat(finding.commentCount).isEqualTo(1)
    }

    @Test
    fun `an unknown finding is null rather than an error`() {
        val subject = newSubject()

        val finding = withMediator { GetQualityFinding(subject.tenantKey, QualityFindingKey.generate()).query() }

        assertThat(finding).isNull()
    }

    /** The lookup is scoped by tenant as well as id, so a guessed id from elsewhere is a 404. */
    @Test
    fun `a finding from another tenant is not visible`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(SubmittedFinding("example.rule", QualitySeverity.WARNING, "fp-1", "off")),
            ).execute()
        }
        val listed = onlyFindingOf(subject)
        val otherSubject = newSubject("Other tenant's template")

        val finding = withMediator { GetQualityFinding(otherSubject.tenantKey, listed.key).query() }

        assertThat(finding).isNull()
    }
}
