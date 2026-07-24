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
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.commands.UnignoreFinding
import app.epistola.suite.quality.queries.GetFindingDispositions
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Ignores are the only user-authored state in the ledger, and the two properties below are the whole
 * reason they are keyed on a fingerprint rather than on a finding row or a version.
 */
class QualityIgnoreIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")

    private fun finding(fingerprint: String, message: String = "something is off") = SubmittedFinding(
        ruleId = "example.rule",
        severity = QualitySeverity.WARNING,
        fingerprint = fingerprint,
        message = message,
    )

    private fun newSubject(): QualitySubject {
        val tenant = createTenant("Quality Ignores")
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
    fun `ignoring a finding records the reason and derives the status`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()

        withMediator { IgnoreFinding(subject.tenantKey, open.key, "Intentional — legal requires this wording").execute() }

        val ignored = readBack(subject).findings.single()
        assertThat(ignored.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(ignored.ignoreReason).isEqualTo("Intentional — legal requires this wording")
        // IGNORED is derived, not stored: the underlying finding is still very much open.
        assertThat(ignored.key).isEqualTo(open.key)
    }

    /**
     * The property that makes ignores usable at all. If ignores were keyed per version, every
     * publish would resurface every previously-dismissed finding and authors would re-ignore the
     * same false positives forever.
     */
    @Test
    fun `an ignore carries across a resubmit`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "false positive").execute() }

        testClock.advanceBy(Duration.ofDays(1))
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }

        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
    }

    /**
     * The counterweight. An ignore must not silently swallow a *different* problem than the one a
     * human actually looked at — a materially changed finding yields a new fingerprint, which no
     * ignore matches, so it comes back for a fresh look.
     */
    @Test
    fun `a materially changed finding resurfaces despite the old ignore`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-old"))).execute() }
        val open = readBack(subject).findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "false positive").execute() }

        // The source now reports a materially different problem: new fingerprint, old one gone.
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-new"))).execute() }

        val byFingerprint = readBack(subject).findings.associateBy { it.fingerprint }
        assertThat(byFingerprint["fp-new"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(byFingerprint["fp-new"]!!.ignoreReason).isNull()
        // The old one auto-resolved; its ignore lingers harmlessly, ready if it ever returns.
        assertThat(byFingerprint["fp-old"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
    }

    /** An ignored problem that gets fixed and later returns should come back already ignored. */
    @Test
    fun `an ignore survives a resolve and resurface cycle`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "by design").execute() }

        withMediator { SubmitQualityFindings(source, subject, emptyList()).execute() }
        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)

        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }

        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
    }

    @Test
    fun `unignoring restores the finding and re-ignoring un-revokes`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "false positive").execute() }

        val lifted = withMediator { UnignoreFinding.ofFinding(subject.tenantKey, open.key).execute() }
        assertThat(lifted).isTrue()
        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)

        withMediator { IgnoreFinding(subject.tenantKey, open.key, "actually fine").execute() }
        val reIgnored = readBack(subject).findings.single()
        assertThat(reIgnored.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(reIgnored.ignoreReason).isEqualTo("actually fine")
    }

    @Test
    fun `unignoring twice is a no-op rather than an error`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()
        withMediator { IgnoreFinding(subject.tenantKey, open.key, "false positive").execute() }

        assertThat(withMediator { UnignoreFinding.ofFinding(subject.tenantKey, open.key).execute() }).isTrue()
        assertThat(withMediator { UnignoreFinding.ofFinding(subject.tenantKey, open.key).execute() }).isFalse()
    }

    /**
     * The disposition feed is what a source polls. Both ignore *and* revoke must land on it — a
     * revocation that vanished from the cursor would leave the source suppressing the finding
     * forever. This is the test that would fail if the soft delete were ever "simplified" away.
     */
    @Test
    fun `the disposition feed reports both ignores and revocations to a source`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()

        withMediator { IgnoreFinding(subject.tenantKey, open.key, "false positive").execute() }

        val afterIgnore = withMediator { GetFindingDispositions(subject.tenantKey, source).query() }
        assertThat(afterIgnore.total).isEqualTo(1)
        assertThat(afterIgnore.items.single()).satisfies({
            assertThat(it.fingerprint).isEqualTo("fp-1")
            assertThat(it.ignored).isTrue()
            assertThat(it.reason).isEqualTo("false positive")
        })

        val cursor = afterIgnore.items.single().changedAt
        testClock.advanceBy(Duration.ofMinutes(5))
        withMediator { UnignoreFinding.ofFinding(subject.tenantKey, open.key).execute() }

        // Polling from the last-seen cursor must surface the revocation.
        val afterRevoke = withMediator { GetFindingDispositions(subject.tenantKey, source, since = cursor).query() }
        assertThat(afterRevoke.items).singleElement().satisfies({
            assertThat(it.fingerprint).isEqualTo("fp-1")
            assertThat(it.ignored).isFalse()
            // The reason described why it *was* ignored; reporting it alongside ignored=false would
            // invite a source to read it as still current.
            assertThat(it.reason).isNull()
        })
    }

    /** A source must only ever see its own dispositions. */
    @Test
    fun `the disposition feed is scoped to one source`() {
        val subject = newSubject()
        val other = QualitySourceId("other")
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-a"))).execute() }
        withMediator { SubmitQualityFindings(other, subject, listOf(finding("fp-b"))).execute() }

        val findings = readBack(subject).findings.associateBy { it.fingerprint }
        withMediator { IgnoreFinding(subject.tenantKey, findings["fp-a"]!!.key, "mine").execute() }
        withMediator { IgnoreFinding(subject.tenantKey, findings["fp-b"]!!.key, "theirs").execute() }

        val mine = withMediator { GetFindingDispositions(subject.tenantKey, source).query() }
        assertThat(mine.items).singleElement().satisfies({ assertThat(it.fingerprint).isEqualTo("fp-a") })
    }

    @Test
    fun `an ignore requires a stated reason`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }
        val open = readBack(subject).findings.single()

        assertThatThrownBy { IgnoreFinding(subject.tenantKey, open.key, "   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
