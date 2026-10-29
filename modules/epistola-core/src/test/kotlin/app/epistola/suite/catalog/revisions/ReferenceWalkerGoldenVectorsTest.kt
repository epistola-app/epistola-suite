// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import db.migration.core.frozen.ReferenceWalker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The reference walker is frozen by golden vectors (ADR 0033, ADR 0035), as the fingerprints are:
 * fixed contents with the references expected in them (`fixtures/references/v1.json`).
 * `resource_references` is built from its output, so a change to what it finds in content that
 * already exists fails here, and needs a code migration that walks the existing content again. A
 * new kind of reference only new content can contain adds a case.
 *
 * The 2.0 upgrade carries a frozen copy of the walker (`db.migration.core.frozen.ReferenceWalker`),
 * which it records the references of converted themes, stencils and variants with; it must agree on
 * every case of a document it walks.
 */
class ReferenceWalkerGoldenVectorsTest {

    private val mapper = JsonMapper.builder().build()

    /** Every content type: each names the references it carries beside the reference sites (ADR 0039). */
    private val types = mapper.let { objectMapper ->
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
    private val vectors: JsonNode = mapper.readTree(javaClass.getResource("/fixtures/references/v1.json")!!.readText())

    private fun expected(case: JsonNode): Set<ContentReference> = case.path("references").toList().map { ref ->
        ContentReference(ref.get("alias")?.takeUnless { it.isNull }?.asString(), ref.path("type").asString(), ref.path("key").asString())
    }.toSet()

    @Test
    fun `the walker finds exactly the references of every golden case`() {
        assertThat(vectors.path("cases").size()).isGreaterThan(5)
        for (case in vectors.path("cases")) {
            assertThat(ContentReferences.of(case.path("content"), types.find(case.path("kind").asString())))
                .`as`(case.path("name").asString())
                .isEqualTo(expected(case))
        }
    }

    @Test
    fun `the upgrade's frozen walker agrees on every document it walks`() {
        val walked = vectors.path("cases").toList().filter { it.path("kind").asString() in setOf("theme", "templateModel") }
        assertThat(walked).isNotEmpty
        for (case in walked) {
            val found = ReferenceWalker.references(case.path("content"))
                .map { ContentReference(it.alias, it.type, it.key) }.toSet()
            assertThat(found).`as`(case.path("name").asString()).isEqualTo(expected(case))
        }
    }
}
