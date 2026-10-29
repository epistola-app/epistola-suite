// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresetDeclaration
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CatalogThemeRef
import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.stencils.stencilDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * The classification rules of [ChangeKindEvaluator] that the renumber tests do not reach: each
 * change against the base, and the kind it is.
 */
class ChangeKindEvaluatorTest {

    /** Every content type, as the application has them (ADR 0039): each says what its changes mean. */
    private val types = ObjectMapper().let { objectMapper ->
        app.epistola.suite.catalog.types.ResourceTypes(
            listOf(
                app.epistola.suite.templates.TemplateResourceType(objectMapper, app.epistola.suite.catalog.ProtocolMapper(objectMapper)),
                app.epistola.suite.stencils.StencilResourceType(),
                app.epistola.suite.attributes.AttributeResourceType(),
                app.epistola.suite.attributes.codelists.CodeListResourceType(),
                app.epistola.suite.themes.ThemeResourceType(),
                app.epistola.suite.fonts.FontResourceType(),
                app.epistola.suite.assets.ImageResourceType(),
            ),
        )
    }

    private val evaluator = ChangeKindEvaluator(types)

    private val document = stencilDocument { build() }

    private fun content(vararg resources: CatalogResource, dependencies: List<CatalogDependency> = emptyList()) = CatalogContent(
        catalog = CatalogInfo("letters", "Letters", null),
        resourceEntries = emptyList(),
        resourceDetails = resources.associate { "${it.type}/${it.key}" to ResourceDetail(CATALOG_SCHEMA_VERSION, it) },
        dependencies = null,
        catalogDependencies = dependencies,
        assetContents = emptyMap(),
    )

    private fun template(dataModel: Map<String, Any?>? = null, vararg variants: Pair<String, Map<String, String>>) = TemplateResource(
        key = "invoice",
        name = "Invoice",
        dataModel = dataModel,
        templateModel = document,
        variants = variants.map { (key, attributes) -> VariantEntry(key = key, name = key, attributes = attributes, templateModel = document) },
    )

    private fun stencil(parameters: Map<String, Any?>?) = StencilResource(key = "letterhead", name = "Letterhead", content = document, parameterSchema = parameters)

    private fun codeList(vararg entries: CodeListEntryEntry) = CodeListResource(key = "colours", name = "Colours", entries = entries.toList())

    private fun dependency(pin: String?) = CatalogDependency(
        registry = "exchange.epistola.app",
        namespace = "acme",
        catalogKey = "shared",
        releaseVersion = pin,
    )

    private fun evaluate(base: CatalogContent, draft: CatalogContent) = evaluator.evaluate(base, draft).reasons.map { it.kind to it.toString() }

    private val nameSchema = mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))

    @Test
    fun `a contract where there was none narrows, and dropping it widens`() {
        assertThat(evaluate(content(template()), content(template(nameSchema))))
            .containsExactly(ChangeKind.BREAKING to "template 'invoice': a data contract was added, which rejects input accepted before")
        assertThat(evaluate(content(template(nameSchema)), content(template())))
            .containsExactly(ChangeKind.ADDITIVE to "template 'invoice': its data contract was removed, so any input is accepted")
    }

    @Test
    fun `a variant removed or with other attributes is breaking, one added additive`() {
        val base = content(template(null, "default" to emptyMap(), "dutch" to mapOf("language" to "nl"), "old" to emptyMap()))
        val draft = content(template(null, "default" to emptyMap(), "dutch" to mapOf("language" to "en"), "new" to emptyMap()))

        assertThat(evaluate(base, draft)).containsExactly(
            ChangeKind.BREAKING to "template 'invoice': variant 'old' removed",
            ChangeKind.ADDITIVE to "template 'invoice': variant 'new' added",
            ChangeKind.BREAKING to "template 'invoice': variant 'dutch' attributes changed from language=nl to language=en",
        )
    }

    @Test
    fun `a stencil parameter added, removed or no longer required is additive`() {
        val base = stencil(mapOf("type" to "object", "properties" to mapOf("title" to mapOf("type" to "string"), "old" to mapOf("type" to "string")), "required" to listOf("title")))
        val draft = stencil(mapOf("type" to "object", "properties" to mapOf("title" to mapOf("type" to "string"), "extra" to mapOf("type" to "string"))))

        assertThat(evaluate(content(base), content(draft))).containsExactly(
            ChangeKind.ADDITIVE to "stencil 'letterhead': parameter 'title' no longer has to be bound",
            ChangeKind.ADDITIVE to "stencil 'letterhead': parameter 'extra' added",
            ChangeKind.ADDITIVE to "stencil 'letterhead': parameter 'old' removed; a binding of it is ignored",
        )
    }

    @Test
    fun `a code-list entry added or relabelled is additive`() {
        val base = codeList(CodeListEntryEntry("red", "Red", 0, false))
        val draft = codeList(CodeListEntryEntry("red", "Rood", 0, false), CodeListEntryEntry("blue", "Blue", 1, false))

        assertThat(evaluate(content(base), content(draft))).containsExactly(
            ChangeKind.ADDITIVE to "codeList 'colours': entry 'blue' added",
            ChangeKind.ADDITIVE to "codeList 'colours': entry 'red' changed",
        )
    }

    @Test
    fun `a dependency added is an addition, one removed no change, and a moved pin breaks only what is used`() {
        val shared = "dependency 'exchange.epistola.app/acme/shared'"
        assertThat(evaluate(content(), content(dependencies = listOf(dependency("1.0.0")))))
            .containsExactly(ChangeKind.ADDITIVE to "$shared: added; an installation brings it along")
        assertThat(evaluate(content(dependencies = listOf(dependency("1.0.0"))), content()))
            .containsExactly(ChangeKind.NONE to "$shared: no longer used")

        // Which moves break is the per-use check's answer, here: only to 2.0.0, and only the footer.
        val perUse = ChangeKindEvaluator(types) { _, _, to -> if (to == "2.0.0") listOf("stencil 'footer': placeholder 'body' removed") else emptyList() }
        fun lockMove(from: String, to: String, with: ChangeKindEvaluator = perUse) = with
            .evaluate(content(dependencies = listOf(dependency(from))), content(dependencies = listOf(dependency(to))))
            .reasons.map { it.kind to it.toString() }
        assertThat(lockMove("1.0.0", "1.1.0")).containsExactly(ChangeKind.NONE to "$shared: now uses v1.1.0 instead of v1.0.0, which fits what is used")
        assertThat(lockMove("1.0.0", "3.0.0")).`as`("a new major that fits").containsExactly(ChangeKind.NONE to "$shared: now uses v3.0.0 instead of v1.0.0, which fits what is used")
        assertThat(lockMove("1.0.0", "2.0.0")).containsExactly(ChangeKind.BREAKING to "$shared: v2.0.0 breaks what is used of it: stencil 'footer': placeholder 'body' removed")
        assertThat(lockMove("1.0.0", "2.0.0", evaluator).map { it.first }).`as`("without a per-use check, a move is a fix").containsExactly(ChangeKind.NONE)
        // Not pinned: there is nothing to compare.
        assertThat(lockMove("1.0.0", "")).isEmpty()
    }

    @Test
    fun `a preset removed is breaking, one added additive, one renamed a fix`() {
        fun withPresets(vararg presets: CatalogPresetDeclaration) = content().let { it.copy(catalog = it.catalog.copyWithMetadata(presets = presets.toList())) }
        val base = withPresets(CatalogPresetDeclaration("formal", "Formal"), CatalogPresetDeclaration("quote", "Quote"))
        val draft = withPresets(CatalogPresetDeclaration("quote", "Quotation"), CatalogPresetDeclaration("note", "Note"))

        assertThat(evaluate(base, draft)).containsExactly(
            ChangeKind.BREAKING to "preset 'formal': removed, while content of a catalog that uses this one may name it",
            ChangeKind.ADDITIVE to "preset 'note': added",
            ChangeKind.NONE to "preset 'quote': renamed or described differently",
        )
    }

    @Test
    fun `another default theme or license is a minor change`() {
        val base = content()
        val themed = content().let { it.copy(catalog = it.catalog.copyWithMetadata(defaultTheme = CatalogThemeRef("brand"))) }
        val licensed = content().let { it.copy(catalog = it.catalog.copyWithMetadata(license = CatalogLicense("Apache License 2.0", "Apache-2.0"))) }

        assertThat(evaluate(base, themed)).containsExactly(
            ChangeKind.ADDITIVE to "catalog 'defaultTheme': templates without a theme of their own render with another one",
        )
        assertThat(evaluate(base, licensed)).containsExactly(ChangeKind.ADDITIVE to "catalog 'license': changed")
        assertThat(evaluate(themed, content().let { it.copy(catalog = it.catalog.copyWithMetadata(defaultTheme = CatalogThemeRef("brand", "letters"))) }))
            .`as`("the catalog's own theme, named with or without its alias").isEmpty()
    }

    @Test
    fun `a version step is the highest part raised over the base`() {
        fun step(base: String, to: String) = VersionStep.between(SemVer.parse(base), SemVer.parse(to))
        assertThat(step("1.4.0", "1.4.2")).isEqualTo(VersionStep.PATCH)
        assertThat(step("1.4.0", "1.5.0")).isEqualTo(VersionStep.MINOR)
        assertThat(step("1.4.0", "2.0.0")).isEqualTo(VersionStep.MAJOR)
        assertThat(step("1.4.0", "1.3.9")).isNull()
        assertThat(step("1.4.0", "1.4.0")).isNull()
    }
}
