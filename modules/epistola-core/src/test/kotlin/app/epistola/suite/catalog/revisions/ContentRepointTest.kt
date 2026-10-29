// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.templates.TemplateResourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Re-pointing a wire form (ADR 0042): a reference [ContentReferences.repoint] is told moved names its
 * new address wherever [ContentReferences.of] finds it, the document sites and what a template
 * carries itself, and nothing else changes.
 */
class ContentRepointTest {
    private val objectMapper = JsonMapper.builder().build()
    private val templates = TemplateResourceType(objectMapper, ProtocolMapper(objectMapper))

    private val moves = mapOf(
        ContentReference(null, "theme", "house") to ContentReference("shared", "theme", "house"),
        ContentReference("letters", "attribute", "region") to ContentReference("shared", "attribute", "area"),
        ContentReference(null, "attribute", "lang") to ContentReference(null, "attribute", "language"),
        ContentReference(null, "stencil", "footer") to ContentReference("shared", "stencil", "footer"),
    )

    @Test
    fun `a template's theme, variant attributes and document references follow what moved`() {
        val template = objectMapper.readTree(
            """
            {"type": "template", "key": "invoice", "themeKey": "house",
             "variants": [{"key": "default", "attributes": {"letters.region": "north", "lang": "nl", "kind": "a"},
               "templateModel": {"nodes": {"s": {"id": "s", "type": "stencil", "props": {"stencilKey": "footer"}}}}}]}
            """,
        ) as ObjectNode

        val changed = ContentReferences.repoint(template, templates) { moves[it] }

        assertThat(changed).isTrue()
        assertThat(template.path("themeKey").asString() to template.path("themeCatalogKey").asString()).isEqualTo("house" to "shared")
        val variant = template.path("variants").first()
        assertThat(variant.path("attributes").properties().associate { it.key to it.value.asString() })
            .containsExactlyEntriesOf(mapOf("shared.area" to "north", "language" to "nl", "kind" to "a"))
        assertThat(ContentReferences.of(template, templates)).contains(ContentReference("shared", "stencil", "footer"))
    }

    @Test
    fun `a template naming nothing that moved is left as it was`() {
        val template = objectMapper.readTree("""{"type": "template", "key": "letter", "themeKey": "plain", "variants": [{"key": "default", "attributes": {"kind": "a"}}]}""") as ObjectNode
        val before = template.deepCopy()

        assertThat(ContentReferences.repoint(template, templates) { moves[it] }).isFalse()
        assertThat(template).isEqualTo(before)
    }
}
