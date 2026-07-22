package app.epistola.suite.quality.sources

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.QualitySubjectType
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The rule mirrors `ImageNodeRenderer`, which is the point: it reports exactly the case where the
 * renderer will stamp a PDF/UA-1 identifier on a document that has no alternate description to back
 * it. If that renderer's contract changes (a new prop, a different decorative flag), these tests are
 * where the mirror cracks.
 */
class AccessibilityQualitySourceTest {
    private val source = AccessibilityQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/invoice/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/invoice",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("invoice"),
        variantKey = "v1",
    )

    private fun imageNode(
        id: String,
        props: Map<String, Any?>,
    ) = Node(id = id, type = "image", slots = emptyList(), props = props)

    private fun inputFor(vararg nodes: Node) = QualityCheckInput(
        subject = subject,
        templateModel = TemplateDocument(
            modelVersion = 1,
            root = nodes.firstOrNull()?.id ?: "root",
            nodes = nodes.associateBy { it.id },
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        ),
        dataExamples = emptyList(),
        dataModel = null,
    )

    @Test
    fun `an image with no alt is reported`() {
        val findings = source.check(inputFor(imageNode("img-1", mapOf("assetId" to "a"))))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(AccessibilityQualitySource.RULE_IMAGE_MISSING_ALT)
            assertThat(it.severity).isEqualTo(QualitySeverity.WARNING)
            assertThat(it.nodeIds).containsExactly("img-1")
            // Self-describing: the reader gets the rule's own reference without a local catalog.
            assertThat(it.docsUrl).isNotNull()
        })
    }

    @Test
    fun `a blank alt is no better than a missing one`() {
        val findings = source.check(inputFor(imageNode("img-1", mapOf("assetId" to "a", "alt" to "   "))))

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `an image with alt text is not reported`() {
        val findings = source.check(inputFor(imageNode("img-1", mapOf("assetId" to "a", "alt" to "Company logo"))))

        assertThat(findings).isEmpty()
    }

    /**
     * A decorative image is silent by design — the renderer marks it an artifact, which is the
     * right answer for a divider, not an omission. Reporting it would train authors to add
     * meaningless alt text to shut the check up, which is worse than saying nothing.
     */
    @Test
    fun `a decorative image is not reported even with no alt`() {
        val findings = source.check(inputFor(imageNode("img-1", mapOf("assetId" to "a", "decorative" to true))))

        assertThat(findings).isEmpty()
    }

    @Test
    fun `non-image nodes are ignored`() {
        val text = Node(id = "t-1", type = "text", slots = emptyList(), props = mapOf("content" to "hi"))

        assertThat(source.check(inputFor(text))).isEmpty()
    }

    @Test
    fun `a node with no props does not throw`() {
        val findings = source.check(inputFor(Node(id = "img-1", type = "image", slots = emptyList(), props = null)))

        // No props means nothing to judge — a source that threw here would be skipped for the run.
        assertThat(findings).isEmpty()
    }

    @Test
    fun `each unlabelled image is reported separately`() {
        val findings = source.check(
            inputFor(
                imageNode("img-1", mapOf("assetId" to "a")),
                imageNode("img-2", mapOf("assetId" to "b")),
            ),
        )

        assertThat(findings).hasSize(2)
        assertThat(findings.map { it.fingerprint }.distinct()).hasSize(2)
    }

    /** Auto-resolve and ignore-carry-forward both rest on this. */
    @Test
    fun `the same image fingerprints identically across runs`() {
        val node = imageNode("img-1", mapOf("assetId" to "a"))

        val first = source.check(inputFor(node)).single().fingerprint
        val second = source.check(inputFor(node)).single().fingerprint

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `the source does not claim the reserved manual id`() {
        assertThat(source.sourceId).isNotEqualTo(QualitySourceId.MANUAL)
    }
}
