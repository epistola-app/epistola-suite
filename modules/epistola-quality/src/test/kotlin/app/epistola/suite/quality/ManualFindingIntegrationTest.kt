package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.AddFindingComment
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.commands.ResolveManualFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetFindingComments
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The human half of the ledger: a reviewer raising a note, and a discussion on a finding.
 *
 * The point of putting these in the same table as machine findings is that they inherit the ignore
 * flow, the report filters and the node highlight for free. The one asymmetry — manual findings do
 * not auto-resolve — is asserted here rather than left to be discovered.
 */
class ManualFindingIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")

    private fun newSubject(): QualitySubject {
        val tenant = createTenant("Quality Manual")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            QualitySubject.of(variantId)
        }
    }

    private fun readBack(subject: QualitySubject) = withMediator {
        GetFindingsForSubject(
            tenantKey = subject.tenantKey,
            catalogKey = subject.catalogKey,
            templateKey = subject.templateKey,
            variantKey = subject.variantKey!!,
        ).query()
    }

    @Test
    fun `a reviewer can raise a finding against a node`() {
        val subject = newSubject()

        withMediator {
            RecordManualFinding(
                subject = subject,
                message = "This paragraph contradicts the terms page",
                severity = QualitySeverity.ERROR,
                nodeIds = listOf("node-abc"),
            ).execute()
        }

        val finding = readBack(subject).findings.single()
        assertThat(finding.sourceId).isEqualTo(QualitySourceId.MANUAL)
        assertThat(finding.message).isEqualTo("This paragraph contradicts the terms page")
        assertThat(finding.severity).isEqualTo(QualitySeverity.ERROR)
        assertThat(finding.nodeIds).containsExactly("node-abc")
        assertThat(finding.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        // The flag that tells the UI to offer a Resolve action — this one will not clear itself.
        assertThat(finding.reconciled).isFalse()
        // A human's remark is not computed against a document revision, so it must never be marked
        // outdated when the draft changes.
        assertThat(finding.inputFingerprint).isNull()
    }

    /**
     * The asymmetry, asserted. An automated submission for the same subject must not resolve a person's
     * note — reconciliation is scoped by source, and `manual` is never a submitting source.
     */
    @Test
    fun `an automated submission never resolves a manual finding`() {
        val subject = newSubject()
        withMediator { RecordManualFinding(subject, "Please reword this").execute() }
        withMediator { SubmitQualityFindings(source, subject, listOf(exampleFinding("fp-1"))).execute() }

        // The source finds nothing this time — its own finding resolves, the human's must not.
        withMediator { SubmitQualityFindings(source, subject, emptyList()).execute() }

        val bySource = readBack(subject).findings.associateBy { it.sourceId }
        assertThat(bySource[source]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
        assertThat(bySource[QualitySourceId.MANUAL]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    @Test
    fun `a manual finding is resolved by a person`() {
        val subject = newSubject()
        val key = withMediator { RecordManualFinding(subject, "Please reword this").execute() }

        val resolved = withMediator { ResolveManualFinding(subject.tenantKey, key).execute() }

        assertThat(resolved).isTrue()
        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
    }

    /** Guarded to the manual source: an automated finding would simply reopen on the next source run. */
    @Test
    fun `resolve-manual refuses an automated finding`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(exampleFinding("fp-1"))).execute() }
        val automated = readBack(subject).findings.single()

        val resolved = withMediator { ResolveManualFinding(subject.tenantKey, automated.key).execute() }

        assertThat(resolved).isFalse()
        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /** A reviewer can point at several elements in one remark, just as a check can. */
    @Test
    fun `a reviewer can raise one finding against several elements`() {
        val subject = newSubject()

        withMediator {
            RecordManualFinding(
                subject = subject,
                message = "These two paragraphs state different delivery times",
                nodeIds = listOf("node-a", "node-b"),
            ).execute()
        }

        val finding = readBack(subject).findings.single()
        assertThat(finding.nodeIds).containsExactly("node-a", "node-b")
        assertThat(finding.primaryNodeId).isEqualTo("node-a")
    }

    /** Two reviewers raising the same concern are two notes — a person never re-submits. */
    @Test
    fun `two manual findings with the same message are distinct`() {
        val subject = newSubject()
        withMediator { RecordManualFinding(subject, "Please reword this").execute() }
        withMediator { RecordManualFinding(subject, "Please reword this").execute() }

        assertThat(readBack(subject).findings).hasSize(2)
    }

    @Test
    fun `a manual finding can be ignored like any other`() {
        val subject = newSubject()
        val key = withMediator { RecordManualFinding(subject, "Nitpick").execute() }

        withMediator { IgnoreFinding(subject.tenantKey, key, "out of scope for this release").execute() }

        val finding = readBack(subject).findings.single()
        assertThat(finding.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(finding.ignoreReason).isEqualTo("out of scope for this release")
    }

    @Test
    fun `comments are recorded against a finding with their author`() {
        val subject = newSubject()
        val key = withMediator { RecordManualFinding(subject, "Please reword this").execute() }

        withMediator { AddFindingComment(subject.tenantKey, key, "Agreed — will fix in the next draft").execute() }

        val comments = withMediator { GetFindingComments(subject.tenantKey, key).query() }
        assertThat(comments).singleElement().satisfies({
            assertThat(it.body).isEqualTo("Agreed — will fix in the next draft")
            assertThat(it.authorName).isNotBlank()
            assertThat(it.authorId).isNotNull()
        })
        assertThat(readBack(subject).findings.single().commentCount).isEqualTo(1)
    }

    /**
     * Why reconciliation reuses a row rather than inserting a new one. A team argues about a finding,
     * the problem gets fixed, then it comes back — and the argument is still there. Insert-on-reopen
     * would silently orphan the discussion.
     */
    @Test
    fun `a discussion survives the finding resolving and coming back`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(exampleFinding("fp-1"))).execute() }
        val finding = readBack(subject).findings.single()
        withMediator { AddFindingComment(subject.tenantKey, finding.key, "This is a false positive, see ADR 12").execute() }

        withMediator { SubmitQualityFindings(source, subject, emptyList()).execute() }
        withMediator { SubmitQualityFindings(source, subject, listOf(exampleFinding("fp-1"))).execute() }

        val reopened = readBack(subject).findings.single()
        assertThat(reopened.key).isEqualTo(finding.key)
        assertThat(reopened.commentCount).isEqualTo(1)
        assertThat(withMediator { GetFindingComments(subject.tenantKey, reopened.key).query() })
            .singleElement()
            .satisfies({ assertThat(it.body).isEqualTo("This is a false positive, see ADR 12") })
    }

    private fun exampleFinding(fingerprint: String) = SubmittedFinding(
        ruleId = "example.rule",
        severity = QualitySeverity.WARNING,
        fingerprint = fingerprint,
        message = "something is off",
    )
}
