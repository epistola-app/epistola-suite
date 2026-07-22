package app.epistola.suite.quality.sources

import app.epistola.catalog.protocol.FontRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityDataRequirement
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.QualitySubjectType
import app.epistola.suite.quality.ResolvedTemplateDependencies
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnresolvedFontQualitySourceTest {
    private val source = UnresolvedFontQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/invoice/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/invoice",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("invoice"),
        variantKey = "v1",
    )

    private fun textNode(
        id: String,
        font: FontRef?,
    ) = Node(
        id = id,
        type = "text",
        slots = emptyList(),
        props = emptyMap<String, Any?>(),
        styles = font?.let { mapOf("fontFamily" to fontValue(it)) },
    )

    private fun fontValue(ref: FontRef): Map<String, String> = buildMap {
        put("slug", ref.slug)
        ref.catalogKey?.let { put("catalogKey", it) }
    }

    private fun inputFor(
        vararg nodes: Node,
        documentFont: FontRef? = null,
        fonts: Map<FontRef, Boolean> = emptyMap(),
    ) = QualityCheckInput(
        subject = subject,
        templateModel = TemplateDocument(
            modelVersion = 1,
            root = nodes.firstOrNull()?.id ?: "root",
            nodes = nodes.associateBy { it.id },
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
            documentStylesOverride = documentFont?.let { mapOf("fontFamily" to fontValue(it)) },
        ),
        dataExamples = emptyList(),
        dataModel = null,
        dependencies = ResolvedTemplateDependencies(fonts = fonts),
    )

    @Test
    fun `the source declares resolved dependency input`() {
        assertThat(source.requirements).containsExactly(QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES)
    }

    @Test
    fun `an unresolved node font is reported`() {
        val missing = FontRef(catalogKey = "system", slug = "missing-sans")
        val findings = source.check(inputFor(textNode("text-1", missing), fonts = mapOf(missing to false)))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.ruleId).isEqualTo(UnresolvedFontQualitySource.RULE_UNRESOLVED_FONT)
            assertThat(it.severity).isEqualTo(QualitySeverity.WARNING)
            assertThat(it.nodeIds).containsExactly("text-1")
            assertThat(it.messageCode).isEqualTo(UnresolvedFontQualitySource.MSG_UNRESOLVED_FONT)
            assertThat(it.context.get("slug").stringValue()).isEqualTo("missing-sans")
            assertThat(it.context.get("catalogKey").stringValue()).isEqualTo("system")
        })
    }

    @Test
    fun `a resolved font is not reported`() {
        val inter = FontRef(catalogKey = "system", slug = "inter")

        assertThat(source.check(inputFor(textNode("text-1", inter), fonts = mapOf(inter to true)))).isEmpty()
    }

    @Test
    fun `a font absent from dependency resolution is treated as unknown`() {
        val missing = FontRef(catalogKey = "system", slug = "missing-sans")

        assertThat(source.check(inputFor(textNode("text-1", missing)))).isEmpty()
    }

    @Test
    fun `a document level unresolved font has no node marker`() {
        val missing = FontRef(catalogKey = null, slug = "missing-sans")
        val findings = source.check(inputFor(documentFont = missing, fonts = mapOf(missing to false)))

        assertThat(findings).singleElement().satisfies({
            assertThat(it.nodeIds).isEmpty()
            assertThat(it.fingerprint).isNotBlank()
        })
    }

    @Test
    fun `the same unresolved font fingerprints identically across runs`() {
        val missing = FontRef(catalogKey = "system", slug = "missing-sans")
        val input = inputFor(textNode("text-1", missing), fonts = mapOf(missing to false))

        val first = source.check(input).single().fingerprint
        val second = source.check(input).single().fingerprint

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `different anchors fingerprint differently`() {
        val missing = FontRef(catalogKey = "system", slug = "missing-sans")
        val findings = source.check(
            inputFor(
                textNode("text-1", missing),
                textNode("text-2", missing),
                fonts = mapOf(missing to false),
            ),
        )

        assertThat(findings.map { it.fingerprint }.distinct()).hasSize(2)
    }

    @Test
    fun `the source does not claim the reserved manual id`() {
        assertThat(source.sourceId).isNotEqualTo(QualitySourceId.MANUAL)
    }
}
