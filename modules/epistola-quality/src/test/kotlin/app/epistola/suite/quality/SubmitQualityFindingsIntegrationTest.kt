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
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration

/**
 * The heart of the ledger: a submission is a source's FULL current set for a subject, and the ledger
 * reconciles against it.
 *
 * Each test here pins one property the rest of the feature leans on — auto-resolve, row-identity
 * stability, and per-source isolation. If these drift, ignores stop carrying forward and findings
 * stop clearing when authors fix them, both silently.
 */
class SubmitQualityFindingsIntegrationTest : IntegrationTestBase() {
    private val sourceA = QualitySourceId("example")
    private val sourceB = QualitySourceId("other")

    private fun finding(
        fingerprint: String,
        message: String = "something is off",
        severity: QualitySeverity = QualitySeverity.WARNING,
        nodeIds: List<String> = emptyList(),
    ) = SubmittedFinding(
        ruleId = "example.rule",
        severity = severity,
        fingerprint = fingerprint,
        message = message,
        nodeIds = nodeIds,
    )

    /** A tenant + template + variant, and the subject naming that variant. */
    private fun newSubject(): QualitySubject {
        val tenant = createTenant("Quality Reconcile")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null).execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null, catalogVersion = null).execute()
            QualitySubject.of(variantId)
        }
    }

    private fun readBack(subject: QualitySubject) = withMediator {
        GetFindingsForSubject(
            catalog = testCatalog(subject.tenantKey, subject.catalogKey),
            templateKey = subject.templateKey,
            variantKey = subject.variantKey!!,
            catalogVersion = null,
        ).query()
    }

    @Test
    fun `a new fingerprint is opened`() {
        val subject = newSubject()

        val result = withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", "heading is empty"))).execute()
        }

        assertThat(result.opened).isEqualTo(1)
        assertThat(result.unchanged).isZero()
        assertThat(result.dropped).isZero()

        val findings = readBack(subject).findings
        assertThat(findings).singleElement().satisfies({
            assertThat(it.fingerprint).isEqualTo("fp-1")
            assertThat(it.message).isEqualTo("heading is empty")
            assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
            assertThat(it.sourceId).isEqualTo(sourceA)
            // An automated source reconciles, so the UI must not offer a manual Resolve action.
            assertThat(it.reconciled).isTrue()
        })
    }

    /**
     * A source re-reporting the same problem must not churn the row: `first_seen_at` is when the
     * problem appeared and stays put, while `last_seen_at` tracks the newest confirmation. If this
     * regresses, "how long has this been broken" silently becomes "when did the source last run".
     */
    @Test
    fun `an identical resubmit keeps first seen and advances last seen`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }
        val first = readBack(subject).findings.single()

        testClock.advanceBy(Duration.ofHours(3))
        val result = withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(result.unchanged).isEqualTo(1)
        assertThat(result.opened).isZero()

        val second = readBack(subject).findings.single()
        assertThat(second.key).isEqualTo(first.key)
        assertThat(second.firstSeenAt).isEqualTo(first.firstSeenAt)
        assertThat(second.lastSeenAt).isAfter(first.lastSeenAt)
        assertThat(second.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /** Display fields may change without the problem materially changing — newest submission wins. */
    @Test
    fun `a resubmit refreshes display fields without reopening`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", "old wording"))).execute() }

        withMediator {
            SubmitQualityFindings(
                sourceA,
                subject,
                listOf(finding("fp-1", "clearer wording", severity = QualitySeverity.ERROR)),
            ).execute()
        }

        val finding = readBack(subject).findings.single()
        assertThat(finding.message).isEqualTo("clearer wording")
        assertThat(finding.severity).isEqualTo(QualitySeverity.ERROR)
    }

    /**
     * The core promise: fix the problem, the source stops reporting it, and it is gone. Not stored as
     * resolved (ADR 0044): what the check shown does not report, nobody needs to see.
     */
    @Test
    fun `a fingerprint the source no longer reports is gone`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"), finding("fp-2"))).execute()
        }

        val result = withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(result.dropped).isEqualTo(1)
        assertThat(result.unchanged).isEqualTo(1)

        assertThat(readBack(subject).findings).singleElement().satisfies({
            assertThat(it.fingerprint).isEqualTo("fp-1")
            assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        })
    }

    /**
     * An empty submission is legal and meaningful — "I checked, nothing is wrong any more".
     *
     * This works because `fingerprint <> ALL('{}')` is TRUE in Postgres, so it needs no special case.
     * That is subtle enough to be worth pinning: a well-meaning "skip the UPDATE when the list is
     * empty" guard would silently strand every open finding as OPEN forever.
     */
    @Test
    fun `an empty submission clears everything the source had reported`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"), finding("fp-2"))).execute()
        }

        val result = withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }

        assertThat(result.dropped).isEqualTo(2)
        assertThat(readBack(subject).findings).isEmpty()
        // Checked, nothing wrong: not the same as never checked.
        assertThat(readBack(subject).checks).singleElement().satisfies({
            assertThat(it.sourceId).isEqualTo(sourceA)
            assertThat(it.current).isTrue()
        })
    }

    /** A problem that goes and comes back keeps its discussion, which belongs to the problem, not to a row. */
    @Test
    fun `a problem that comes back keeps its comments`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }
        val original = readBack(subject).findings.single()
        withMediator { app.epistola.suite.quality.commands.AddFindingComment(subject.tenantKey, original.key, "Still wrong.").execute() }

        withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }
        assertThat(readBack(subject).findings).isEmpty()

        testClock.advanceBy(Duration.ofHours(1))
        val result = withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(result.opened).isEqualTo(1)
        val back = readBack(subject).findings.single()
        assertThat(back.commentCount).isEqualTo(1)
        assertThat(back.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /**
     * Reconciliation is scoped by source id. Without that scoping, every source would resolve every
     * other source's findings on each submission — and the last one to run would win.
     */
    @Test
    fun `a submission never touches another source's findings`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-a"))).execute() }
        withMediator { SubmitQualityFindings(sourceB, subject, listOf(finding("fp-b"))).execute() }

        // A submits an empty set — it may clear only its own.
        withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }

        assertThat(readBack(subject).findings.map { it.fingerprint }).containsExactly("fp-b")
    }

    /** Findings for one variant must not be resolved by a submission for a sibling variant. */
    @Test
    fun `a submission never touches another subject's findings`() {
        val first = newSubject()
        val second = withMediator {
            val variantId = VariantId(
                TestIdHelpers.nextVariantId(),
                TemplateId(first.templateKey, CatalogId.default(TenantId(first.tenantKey))),
            )
            CreateVariant(id = variantId, title = "Second", description = null, catalogVersion = null).execute()
            QualitySubject.of(variantId)
        }

        withMediator { SubmitQualityFindings(sourceA, first, listOf(finding("fp-1"))).execute() }
        withMediator { SubmitQualityFindings(sourceA, second, listOf(finding("fp-1"))).execute() }

        withMediator { SubmitQualityFindings(sourceA, first, emptyList()).execute() }

        assertThat(readBack(first).findings).isEmpty()
        assertThat(readBack(second).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /**
     * A finding is stored under the input key its check read, which the ledger computes from what
     * the version holds (`quality_input_key`), never one a source made up.
     */
    @Test
    fun `a finding is stored under the variant's input key and is current while it is unchanged`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        val read = readBack(subject)
        val finding = read.findings.single()

        assertThat(finding.inputKey).isNotNull().hasSize(64)
        assertThat(finding.inputKey).isEqualTo(read.currentInputKey)
        assertThat(finding.outdated).isFalse()
    }

    /**
     * A finding can be about several elements at once — "these two paragraphs contradict each
     * other" is one problem, not two. Each named node gets a marker in the editor; the first is
     * where "go to" lands.
     */
    @Test
    fun `a finding can mark several elements at once`() {
        val subject = newSubject()

        withMediator {
            SubmitQualityFindings(
                sourceA,
                subject,
                listOf(
                    finding(
                        "fp-1",
                        message = "These paragraphs state different delivery times",
                        nodeIds = listOf("node-a", "node-b", "node-c"),
                    ),
                ),
            ).execute()
        }

        val finding = readBack(subject).findings.single()
        // Order is the source's own order of relevance and must survive the round trip.
        assertThat(finding.nodeIds).containsExactly("node-a", "node-b", "node-c")
        assertThat(finding.primaryNodeId).isEqualTo("node-a")
        assertThat(finding.marks("node-b")).isTrue()
        assertThat(finding.marks("node-z")).isFalse()
    }

    /** A finding about the document as a whole names no element, and that is not an error. */
    @Test
    fun `a finding can mark no element at all`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        val finding = readBack(subject).findings.single()
        assertThat(finding.nodeIds).isEmpty()
        assertThat(finding.primaryNodeId).isNull()
    }

    /** The marked set is a display field: a source may add or drop a node without it becoming a new problem. */
    @Test
    fun `a resubmit updates the marked elements without reopening`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", nodeIds = listOf("node-a")))).execute()
        }
        val first = readBack(subject).findings.single()

        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", nodeIds = listOf("node-a", "node-b")))).execute()
        }

        val second = readBack(subject).findings.single()
        assertThat(second.key).isEqualTo(first.key)
        assertThat(second.nodeIds).containsExactly("node-a", "node-b")
        assertThat(second.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /** A repeated node would mean two markers on one element — a source bug, caught early. */
    @Test
    fun `a finding rejects duplicate element references`() {
        assertThatThrownBy { finding("fp-1", nodeIds = listOf("node-a", "node-a")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("node-a")
    }

    /** The reserved manual source must not be reachable through the reconciling path. */
    @Test
    fun `the manual source cannot be submitted through reconciliation`() {
        val subject = newSubject()

        assertThatThrownBy {
            // The constructor's own checks, before any key is read.
            SubmitQualityFindings(QualitySourceId.MANUAL, subject, INPUT_KEY, listOf(finding("fp-1")), catalogVersion = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("RecordManualFinding")
    }

    /**
     * A duplicate fingerprint in one submission is a source bug: the two entries would race to be the
     * winning upsert and the loser would vanish without trace. Fail loudly instead.
     */
    @Test
    fun `a submission with duplicate fingerprints is rejected`() {
        val subject = newSubject()

        assertThatThrownBy {
            SubmitQualityFindings(sourceA, subject, INPUT_KEY, listOf(finding("fp-1", "a"), finding("fp-1", "b")), catalogVersion = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fp-1")
    }

    /**
     * The i18n key and both jsonb bags survive the round trip, kept distinct — the ledger stores
     * evidence, operational metadata and the message code without collapsing any into another.
     */
    @Test
    fun `message code, context and metadata round-trip and stay separate`() {
        val subject = newSubject()
        val submitted = SubmittedFinding(
            ruleId = "example.long-text",
            severity = QualitySeverity.INFO,
            fingerprint = "fp-1",
            message = "This block is 879 characters long.",
            messageCode = "quality.example.long-text",
            context = JsonNodeFactory.instance.objectNode().put("length", 879),
            metadata = JsonNodeFactory.instance.objectNode().put("checkerVersion", "2.3"),
        )

        withMediator { SubmitQualityFindings(sourceA, subject, listOf(submitted)).execute() }
        val finding = readBack(subject).findings.single()

        assertThat(finding.messageCode).isEqualTo("quality.example.long-text")
        // Evidence, for the reader.
        assertThat(finding.context.get("length").asInt()).isEqualTo(879)
        // Operational, never shown — and NOT leaked into context.
        assertThat(finding.metadata.get("checkerVersion").stringValue()).isEqualTo("2.3")
        assertThat(finding.context.has("checkerVersion")).isFalse()
    }

    /** A resubmit rewords the message, its code and its metadata to the newest, on the same row. */
    @Test
    fun `a resubmit updates message code and metadata to the newest`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                sourceA,
                subject,
                listOf(
                    SubmittedFinding(
                        ruleId = "r",
                        severity = QualitySeverity.INFO,
                        fingerprint = "fp-1",
                        message = "old",
                        messageCode = "quality.old",
                        metadata = JsonNodeFactory.instance.objectNode().put("checkerVersion", "1.0"),
                    ),
                ),
            ).execute()
        }

        withMediator {
            SubmitQualityFindings(
                sourceA,
                subject,
                listOf(
                    SubmittedFinding(
                        ruleId = "r",
                        severity = QualitySeverity.INFO,
                        fingerprint = "fp-1",
                        message = "new",
                        messageCode = "quality.new",
                        metadata = JsonNodeFactory.instance.objectNode().put("checkerVersion", "2.0"),
                    ),
                ),
            ).execute()
        }

        val finding = readBack(subject).findings.single()
        assertThat(finding.messageCode).isEqualTo("quality.new")
        assertThat(finding.metadata.get("checkerVersion").stringValue()).isEqualTo("2.0")
    }

    /** A source that does not localize leaves the code null rather than inventing one. */
    @Test
    fun `a finding without a message code reads back null`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(readBack(subject).findings.single().messageCode).isNull()
    }

    private companion object {
        /** Any key: these submissions are refused before one is read. */
        const val INPUT_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
