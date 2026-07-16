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
 * A source is a pure function from a document to findings, so it tests with a plain fixture and no
 * Spring context. That is the property the SPI exists to preserve.
 *
 * The fingerprint tests matter more than the rule tests: the rules here are throwaway, but the
 * fingerprint contract is what every future source has to honour, and getting it wrong breaks
 * auto-resolve and ignore-carry-forward silently.
 */
class ExampleQualitySourceTest {
    private val source = ExampleQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/invoice/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/invoice",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("invoice"),
        variantKey = "v1",
    )

    /** A ProseMirror doc holding one paragraph of [text]. */
    private fun proseMirrorDoc(text: String): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )

    private fun documentWith(vararg nodes: Node) = TemplateDocument(
        modelVersion = 1,
        root = nodes.firstOrNull()?.id ?: "root",
        nodes = nodes.associateBy { it.id },
        slots = emptyMap(),
        themeRef = ThemeRefInherit(),
    )

    private fun textNode(id: String, text: String) = Node(
        id = id,
        type = "text",
        slots = emptyList(),
        props = mapOf("content" to proseMirrorDoc(text)),
    )

    private fun inputFor(document: TemplateDocument) = QualityCheckInput(
        subject = subject,
        templateModel = document,
        dataExamples = emptyList(),
        dataModel = null,
    )

    @Test
    fun `an empty text node is reported`() {
        val findings = source.check(inputFor(documentWith(textNode("node-1", "   "))))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(ExampleQualitySource.RULE_EMPTY_TEXT)
            assertThat(it.severity).isEqualTo(QualitySeverity.WARNING)
            // These rules are about one block each, so they name one node — but through the same
            // list API a consistency rule would use to name several.
            assertThat(it.nodeIds).containsExactly("node-1")
            assertThat(it.primaryNodeId).isEqualTo("node-1")
        })
    }

    @Test
    fun `an overlong text node is reported with its length as evidence`() {
        val long = "a".repeat(ExampleQualitySource.LONG_TEXT_THRESHOLD + 1)

        val findings = source.check(inputFor(documentWith(textNode("node-1", long))))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(ExampleQualitySource.RULE_LONG_TEXT)
            assertThat(it.severity).isEqualTo(QualitySeverity.INFO)
            assertThat(it.context.get("length").asInt()).isEqualTo(long.length)
        })
    }

    @Test
    fun `a healthy document produces nothing`() {
        val findings = source.check(inputFor(documentWith(textNode("node-1", "Dear customer, your invoice is attached."))))

        assertThat(findings).isEmpty()
    }

    @Test
    fun `non-text nodes are ignored`() {
        val image = Node(id = "img-1", type = "image", slots = emptyList(), props = mapOf("assetId" to "x"))

        assertThat(source.check(inputFor(documentWith(image)))).isEmpty()
    }

    /** Text is collected across the ProseMirror tree, not just the first paragraph. */
    @Test
    fun `text is collected from nested prosemirror content`() {
        val nested = Node(
            id = "node-1",
            type = "text",
            slots = emptyList(),
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Hello "))),
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "brave "),
                                mapOf("type" to "text", "text" to "world"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Non-blank overall, so neither rule fires — proving the text was actually found. A
        // shallow walk would see the doc as blank and wrongly report it empty.
        assertThat(source.check(inputFor(documentWith(nested)))).isEmpty()
    }

    /**
     * A source runs over whatever is stored, including documents from an older editor. Throwing
     * mid-sweep would take out the whole tenant run, so an unrecognised shape means "nothing wrong".
     */
    @Test
    fun `a malformed content prop does not throw`() {
        val odd = Node(id = "node-1", type = "text", slots = emptyList(), props = mapOf("content" to 42))
        val missing = Node(id = "node-2", type = "text", slots = emptyList(), props = emptyMap())

        // Both read as blank text, which is a finding — the point is that neither throws.
        assertThat(source.check(inputFor(documentWith(odd, missing))))
            .allSatisfy { assertThat(it.ruleId).isEqualTo(ExampleQualitySource.RULE_EMPTY_TEXT) }
    }

    @Test
    fun `the same problem fingerprints identically across runs`() {
        val document = documentWith(textNode("node-1", ""))

        val first = source.check(inputFor(document)).single()
        val second = source.check(inputFor(document)).single()

        assertThat(first.fingerprint).isEqualTo(second.fingerprint)
    }

    /**
     * The judgement call, pinned. Editing a too-long block that stays too long is the *same* problem
     * — so it keeps its fingerprint, and therefore keeps any ignore. Were the text in the
     * fingerprint, every keystroke would resurface a finding the author had already dismissed.
     */
    @Test
    fun `editing an overlong block that stays overlong keeps its fingerprint`() {
        val before = source.check(inputFor(documentWith(textNode("node-1", "a".repeat(700))))).single()
        val after = source.check(inputFor(documentWith(textNode("node-1", "b".repeat(900))))).single()

        assertThat(after.fingerprint).isEqualTo(before.fingerprint)
    }

    /** Different nodes and different rules are different problems, and must not share an ignore. */
    @Test
    fun `different nodes and rules fingerprint differently`() {
        val empty = source.check(inputFor(documentWith(textNode("node-1", "")))).single()
        val otherEmpty = source.check(inputFor(documentWith(textNode("node-2", "")))).single()
        val long = source.check(inputFor(documentWith(textNode("node-1", "a".repeat(700))))).single()

        assertThat(empty.fingerprint).isNotEqualTo(otherEmpty.fingerprint)
        assertThat(empty.fingerprint).isNotEqualTo(long.fingerprint)
    }

    /**
     * The same node in a different variant is a different problem — otherwise ignoring a finding on
     * one variant would silently dismiss it on a sibling.
     */
    @Test
    fun `the same node under a different subject fingerprints differently`() {
        val document = documentWith(textNode("node-1", ""))
        val otherSubject = subject.copy(urn = "urn:epistola:variant:acme/default/invoice/v2", variantKey = "v2")

        val first = source.check(inputFor(document)).single()
        val second = source.check(
            QualityCheckInput(otherSubject, document, emptyList(), null),
        ).single()

        assertThat(first.fingerprint).isNotEqualTo(second.fingerprint)
    }

    @Test
    fun `the source does not claim the reserved manual id`() {
        assertThat(source.sourceId).isNotEqualTo(QualitySourceId.MANUAL)
    }
}
