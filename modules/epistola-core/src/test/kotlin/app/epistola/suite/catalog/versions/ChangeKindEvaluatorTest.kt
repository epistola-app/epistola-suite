// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogResource
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

    private val evaluator = ChangeKindEvaluator(ObjectMapper())

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
        variants = variants.map { (key, attributes) -> VariantEntry(key = key, title = key, attributes = attributes, templateModel = document) },
    )

    private fun stencil(parameters: Map<String, Any?>?) = StencilResource(key = "letterhead", name = "Letterhead", content = document, parameterSchema = parameters)

    private fun codeList(vararg entries: CodeListEntryEntry) = CodeListResource(key = "colours", name = "Colours", entries = entries.toList())

    private fun dependency(range: String, lock: String?) = CatalogDependency(
        registry = "exchange.epistola.app",
        namespace = "acme",
        catalogKey = "shared",
        version = range,
        releaseVersion = lock,
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
    fun `a dependency added or removed is breaking, and a moved lock is as big as the move`() {
        val shared = "dependency 'exchange.epistola.app/acme/shared'"
        assertThat(evaluate(content(), content(dependencies = listOf(dependency("^1.0.0", "1.0.0")))))
            .containsExactly(ChangeKind.BREAKING to "$shared: added; an installation needs it")
        assertThat(evaluate(content(dependencies = listOf(dependency("^1.0.0", "1.0.0"))), content()))
            .containsExactly(ChangeKind.BREAKING to "$shared: removed")
        fun lockMove(from: String, to: String) = evaluate(content(dependencies = listOf(dependency(">=1.0.0", from))), content(dependencies = listOf(dependency(">=1.0.0", to))))
        assertThat(lockMove("1.0.0", "1.0.1")).isEmpty()
        assertThat(lockMove("1.0.0", "1.1.0")).containsExactly(ChangeKind.ADDITIVE to "$shared: locked to 1.1.0 instead of 1.0.0")
        assertThat(lockMove("1.0.0", "2.0.0")).containsExactly(ChangeKind.BREAKING to "$shared: locked to 2.0.0 instead of 1.0.0")
        assertThat(lockMove("1.1.0", "1.0.0")).containsExactly(ChangeKind.BREAKING to "$shared: locked to 1.0.0 instead of 1.1.0")
        // Not locked yet: it is locked when the draft is released, so there is nothing to compare.
        assertThat(lockMove("1.0.0", "")).isEmpty()
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
