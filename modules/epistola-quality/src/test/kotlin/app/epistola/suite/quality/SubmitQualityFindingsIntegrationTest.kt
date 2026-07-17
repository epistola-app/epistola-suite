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
    fun `a new fingerprint is opened`() {
        val subject = newSubject()

        val result = withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", "heading is empty"))).execute()
        }

        assertThat(result.opened).isEqualTo(1)
        assertThat(result.reopened).isZero()
        assertThat(result.unchanged).isZero()
        assertThat(result.resolved).isZero()

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
     * regresses, "how long has this been broken" silently becomes "when did the sweep last run".
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

    /** The core promise: fix the problem, the source stops reporting it, it closes by itself. */
    @Test
    fun `a fingerprint the source no longer reports is resolved`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"), finding("fp-2"))).execute()
        }

        val result = withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(result.resolved).isEqualTo(1)
        assertThat(result.unchanged).isEqualTo(1)

        val byFingerprint = readBack(subject).findings.associateBy { it.fingerprint }
        assertThat(byFingerprint["fp-1"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(byFingerprint["fp-2"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
        assertThat(byFingerprint["fp-2"]!!.resolvedAt).isNotNull()
    }

    /**
     * An empty submission is legal and meaningful — "I checked, nothing is wrong any more".
     *
     * This works because `fingerprint <> ALL('{}')` is TRUE in Postgres, so it needs no special case.
     * That is subtle enough to be worth pinning: a well-meaning "skip the UPDATE when the list is
     * empty" guard would silently strand every open finding as OPEN forever.
     */
    @Test
    fun `an empty submission resolves everything the source had open`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"), finding("fp-2"))).execute()
        }

        val result = withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }

        assertThat(result.resolved).isEqualTo(2)
        assertThat(readBack(subject).findings)
            .allSatisfy { assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED) }
    }

    /**
     * A resurfacing problem must reuse its original row, not insert a new one — that row identity is
     * what lets comments (a reviewer's discussion) survive a resolve/resurface cycle.
     */
    @Test
    fun `a resolved fingerprint reopens on its original row`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }
        val original = readBack(subject).findings.single()

        withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }
        assertThat(readBack(subject).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)

        val result = withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(result.reopened).isEqualTo(1)
        assertThat(result.opened).isZero()

        val reopened = readBack(subject).findings.single()
        assertThat(reopened.key).isEqualTo(original.key)
        assertThat(reopened.firstSeenAt).isEqualTo(original.firstSeenAt)
        assertThat(reopened.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(reopened.resolvedAt).isNull()
    }

    /**
     * Reconciliation is scoped by source id. Without that scoping, every source would resolve every
     * other source's findings on each sweep — and the last one to run would win.
     */
    @Test
    fun `a submission never touches another source's findings`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-a"))).execute() }
        withMediator { SubmitQualityFindings(sourceB, subject, listOf(finding("fp-b"))).execute() }

        // A submits an empty set — it may resolve only its own.
        withMediator { SubmitQualityFindings(sourceA, subject, emptyList()).execute() }

        val byFingerprint = readBack(subject).findings.associateBy { it.fingerprint }
        assertThat(byFingerprint["fp-a"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
        assertThat(byFingerprint["fp-b"]!!.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /** Findings for one variant must not be resolved by a sweep of a sibling variant. */
    @Test
    fun `a submission never touches another subject's findings`() {
        val first = newSubject()
        val second = withMediator {
            val variantId = VariantId(
                TestIdHelpers.nextVariantId(),
                TemplateId(first.templateKey, CatalogId.default(TenantId(first.tenantKey))),
            )
            CreateVariant(id = variantId, title = "Second", description = null).execute()
            QualitySubject.of(variantId)
        }

        withMediator { SubmitQualityFindings(sourceA, first, listOf(finding("fp-1"))).execute() }
        withMediator { SubmitQualityFindings(sourceA, second, listOf(finding("fp-1"))).execute() }

        withMediator { SubmitQualityFindings(sourceA, first, emptyList()).execute() }

        assertThat(readBack(first).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
        assertThat(readBack(second).findings.single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /**
     * The ledger stamps the input fingerprint itself. A source cannot supply one — there is nowhere
     * on [SubmittedFinding] to put it — precisely so a remote source's differently-normalized hash
     * can never make its findings read as permanently stale.
     */
    @Test
    fun `the ledger stamps the input fingerprint from the live document`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        val read = readBack(subject)
        val finding = read.findings.single()

        assertThat(finding.inputFingerprint).isNotNull()
        assertThat(finding.inputFingerprint).isEqualTo(read.currentInputFingerprint)
        assertThat(read.isStale(finding)).isFalse()
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
            SubmitQualityFindings(QualitySourceId.MANUAL, subject, listOf(finding("fp-1")))
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
            SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1", "a"), finding("fp-1", "b")))
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
        assertThat(finding.metadata.get("checkerVersion").asText()).isEqualTo("2.3")
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
        assertThat(finding.metadata.get("checkerVersion").asText()).isEqualTo("2.0")
    }

    /** A source that does not localize leaves the code null rather than inventing one. */
    @Test
    fun `a finding without a message code reads back null`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(sourceA, subject, listOf(finding("fp-1"))).execute() }

        assertThat(readBack(subject).findings.single().messageCode).isNull()
    }
}
