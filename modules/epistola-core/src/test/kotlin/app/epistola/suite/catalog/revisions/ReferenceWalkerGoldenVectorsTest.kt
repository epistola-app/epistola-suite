// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import db.migration.core.V20261024114440__core_themes_to_storage
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
 * The theme conversion carries a frozen copy of the walker; it must agree on every theme case.
 */
class ReferenceWalkerGoldenVectorsTest {

    private val mapper = JsonMapper.builder().build()
    private val vectors: JsonNode = mapper.readTree(javaClass.getResource("/fixtures/references/v1.json")!!.readText())

    private fun expected(case: JsonNode): Set<ContentReference> = case.path("references").toList().map { ref ->
        ContentReference(ref.get("alias")?.takeUnless { it.isNull }?.asString(), ref.path("type").asString(), ref.path("key").asString())
    }.toSet()

    @Test
    fun `the walker finds exactly the references of every golden case`() {
        assertThat(vectors.path("cases").size()).isGreaterThan(5)
        for (case in vectors.path("cases")) {
            assertThat(ContentReferences.of(case.path("kind").asString(), case.path("content")))
                .`as`(case.path("name").asString())
                .isEqualTo(expected(case))
        }
    }

    @Test
    fun `the theme conversion's frozen walker agrees on every theme case`() {
        for (case in vectors.path("cases").toList().filter { it.path("kind").asString() == "theme" }) {
            val found = V20261024114440__core_themes_to_storage.references(case.path("content"))
                .map { ContentReference(it.alias, it.type, it.key) }.toSet()
            assertThat(found).`as`(case.path("name").asString()).isEqualTo(expected(case))
        }
    }
}
