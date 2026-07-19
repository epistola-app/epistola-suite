package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
import org.springframework.beans.factory.annotation.Autowired

/**
 * The sweep is what keeps the report populated without anyone opening an editor.
 *
 * The idempotence test is the important one: the task lease can expire mid-run and the same
 * occurrence be retried on another node, so a sweep that churned the ledger would corrupt
 * `first_seen_at` (and, if it resolved-then-reopened, the finding's history) every time a node was
 * slow.
 */
class QualityCheckSchedulerIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var scheduler: QualityCheckScheduler

    private fun documentWithEmptyText(): TemplateDocument {
        val node = Node(
            id = "node-1",
            type = "text",
            slots = emptyList(),
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(mapOf("type" to "paragraph", "content" to emptyList<Any>())),
                ),
            ),
        )
        return TemplateDocument(
            modelVersion = 1,
            root = node.id,
            nodes = mapOf(node.id to node),
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        )
    }

    /** A tenant with the quality feature on and one variant containing an empty text block. */
    private fun tenantWithFinding(qualityEnabled: Boolean = true): VariantId {
        val tenant = createTenant("Quality Sweep")
        return withMediator {
            if (qualityEnabled) {
                SaveFeatureToggle(tenantKey = tenant.id, featureKey = KnownFeatures.QUALITY, enabled = true).execute()
            }
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            UpdateDraft(variantId, documentWithEmptyText()).execute()
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
    }.findings

    @Test
    fun `the sweep populates the ledger for a tenant with the feature on`() {
        val variantId = tenantWithFinding()

        scheduler.runDailySweep()

        assertThat(findingsFor(variantId)).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(ExampleQualitySource.RULE_EMPTY_TEXT)
            assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        })
    }

    /** The feature defaults off, so a tenant that has not opted in must be left entirely alone. */
    @Test
    fun `the sweep skips a tenant without the feature`() {
        val variantId = tenantWithFinding(qualityEnabled = false)

        scheduler.runDailySweep()

        assertThat(findingsFor(variantId)).isEmpty()
    }

    /**
     * A lease can expire and the same occurrence be retried on another node, so running twice
     * back-to-back must be indistinguishable from running once.
     */
    @Test
    fun `running the sweep twice is idempotent`() {
        val variantId = tenantWithFinding()

        scheduler.runDailySweep()
        val first = findingsFor(variantId).single()
        scheduler.runDailySweep()
        val second = findingsFor(variantId).single()

        assertThat(second.key).isEqualTo(first.key)
        assertThat(second.firstSeenAt).isEqualTo(first.firstSeenAt)
        assertThat(second.effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)
        assertThat(second.resolvedAt).isNull()
    }

    /** One tenant's failure must not cost the others their sweep. */
    @Test
    fun `the sweep continues past a tenant that has no checkable variants`() {
        val empty = createTenant("Quality Sweep Empty")
        withMediator { SaveFeatureToggle(tenantKey = empty.id, featureKey = KnownFeatures.QUALITY, enabled = true).execute() }
        val variantId = tenantWithFinding()

        scheduler.runDailySweep()

        assertThat(findingsFor(variantId)).hasSize(1)
    }

    /** The sweep resolves what an author fixed between runs, with nobody clicking anything. */
    @Test
    fun `the sweep resolves a finding the author fixed between runs`() {
        val variantId = tenantWithFinding()
        scheduler.runDailySweep()
        assertThat(findingsFor(variantId).single().effectiveStatus).isEqualTo(EffectiveQualityStatus.OPEN)

        withMediator {
            val fixed = documentWithEmptyText().let { doc ->
                val node = doc.nodes.getValue("node-1")
                doc.copy(
                    nodes = mapOf(
                        "node-1" to node.copy(
                            props = mapOf(
                                "content" to mapOf(
                                    "type" to "doc",
                                    "content" to listOf(
                                        mapOf(
                                            "type" to "paragraph",
                                            "content" to listOf(mapOf("type" to "text", "text" to "Now it says something.")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
            UpdateDraft(variantId, fixed).execute()
        }

        scheduler.runDailySweep()

        assertThat(findingsFor(variantId).single().effectiveStatus).isEqualTo(EffectiveQualityStatus.RESOLVED)
    }
}
