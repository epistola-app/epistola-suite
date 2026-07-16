package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.sources.ExampleQualitySource
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The whole feature, end to end, through the real source registry: a check runs, a finding appears
 * in the ledger, an author fixes the document, and the finding closes **by itself** on the next run.
 *
 * Nothing in this test calls "resolve". That is the point — resolution-on-fix falls out of a
 * submission being a full set, and this is the test that proves it holds through the real wiring
 * rather than only in the reconciler's own unit-level tests.
 */
class RunQualityChecksIntegrationTest : IntegrationTestBase() {
    private fun proseMirrorDoc(text: String): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text))),
        ),
    )

    private fun documentWithText(text: String): TemplateDocument {
        val node = Node(
            id = "node-1",
            type = "text",
            slots = emptyList(),
            props = mapOf("content" to proseMirrorDoc(text)),
        )
        return TemplateDocument(
            modelVersion = 1,
            root = node.id,
            nodes = mapOf(node.id to node),
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        )
    }

    private fun newVariant(): VariantId {
        val tenant = createTenant("Quality Run")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            variantId
        }
    }

    private fun findingsFor(variantId: VariantId) = withMediator {
        GetFindingsForSubject(
            tenantKey = variantId.tenantKey,
            catalogKey = variantId.catalogKey,
            templateKey = variantId.templateKey,
            variantKey = variantId.key.value,
        ).query()
    }

    /** The headline round trip. */
    @Test
    fun `a finding opens when a check reports it and closes when the author fixes it`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }

        withMediator { RunQualityChecks(variantId).execute() }

        val open = findingsFor(variantId).findings
        assertThat(open).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(ExampleQualitySource.RULE_EMPTY_TEXT)
            assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
            assertThat(it.nodeId).isEqualTo("node-1")
        })

        // The author fills the block in and the check runs again. Nobody resolves anything.
        withMediator { UpdateDraft(variantId, documentWithText("Dear customer, your invoice is attached.")).execute() }
        withMediator { RunQualityChecks(variantId).execute() }

        assertThat(findingsFor(variantId).findings)
            .singleElement()
            .satisfies({ assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED) })
    }

    @Test
    fun `re-running against an unchanged draft is idempotent`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }

        withMediator { RunQualityChecks(variantId).execute() }
        val first = findingsFor(variantId).findings.single()
        withMediator { RunQualityChecks(variantId).execute() }
        val second = findingsFor(variantId).findings.single()

        // Same row, still open — a re-run must not churn the ledger. This is what makes the sweep
        // safe to retry after a lease expiry.
        assertThat(second.key).isEqualTo(first.key)
        assertThat(second.firstSeenAt).isEqualTo(first.firstSeenAt)
        assertThat(second.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
    }

    /** An ignore is a durable decision — a later sweep must not undo it. */
    @Test
    fun `an ignore survives a later check run`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }
        withMediator { RunQualityChecks(variantId).execute() }
        val finding = findingsFor(variantId).findings.single()
        withMediator { IgnoreFinding(variantId.tenantKey, finding.key, "placeholder, filled at render time").execute() }

        withMediator { RunQualityChecks(variantId).execute() }

        val after = findingsFor(variantId).findings.single()
        assertThat(after.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(after.ignoreReason).isEqualTo("placeholder, filled at render time")
    }

    @Test
    fun `checks run against the draft rather than the published version`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }

        withMediator { RunQualityChecks(variantId).execute() }
        val read = findingsFor(variantId)

        // The finding is stamped with the draft's hash, so the editor shows it as current, not stale.
        val finding = read.findings.single()
        assertThat(finding.inputFingerprint).isEqualTo(read.currentInputFingerprint)
        assertThat(read.isStale(finding)).isFalse()
    }

    /**
     * Staleness, end to end: a finding computed before an edit must be marked outdated rather than
     * presented as a current claim about a document it never saw.
     */
    @Test
    fun `a finding computed before an edit reads as stale afterwards`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }
        withMediator { RunQualityChecks(variantId).execute() }

        // Edit the draft without re-running the checks — exactly what the editor sees mid-session.
        withMediator { UpdateDraft(variantId, documentWithText("   ")).execute() }

        val read = findingsFor(variantId)
        val finding = read.findings.single()
        assertThat(read.isStale(finding)).isTrue()

        // Re-running clears the staleness.
        withMediator { RunQualityChecks(variantId).execute() }
        val rechecked = findingsFor(variantId)
        assertThat(rechecked.isStale(rechecked.findings.single())).isFalse()
    }

    @Test
    fun `a variant with no findings produces an empty ledger`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("Dear customer, your invoice is attached.")).execute() }

        withMediator { RunQualityChecks(variantId).execute() }

        assertThat(findingsFor(variantId).findings).isEmpty()
    }

    /** Restricting to a subset is what the editor's per-panel "Check now" uses. */
    @Test
    fun `running a source subset skips the others`() {
        val variantId = newVariant()
        withMediator { UpdateDraft(variantId, documentWithText("")).execute() }

        val results = withMediator { RunQualityChecks(variantId, sourceIds = setOf(QualitySourceId("nope"))).execute() }

        assertThat(results).isEmpty()
        assertThat(findingsFor(variantId).findings).isEmpty()
    }
}
