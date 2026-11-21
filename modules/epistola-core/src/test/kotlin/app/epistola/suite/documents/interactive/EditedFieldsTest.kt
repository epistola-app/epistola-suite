// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class EditedFieldsTest {
    private val mapper = ObjectMapper()

    private fun json(text: String): ObjectNode = mapper.readValue(text, ObjectNode::class.java)

    @Test
    fun `nothing changed is nothing edited`() {
        assertThat(EditedFields.between(json("""{"a": 1, "b": {"c": "x"}}"""), json("""{"b": {"c": "x"}, "a": 1}"""))).isEmpty()
    }

    @Test
    fun `changed, added, cleared and removed values are reported at their pointers`() {
        val caller = json("""{"citizen": {"street": "Kerkstraat 1", "city": "Sittard", "phone": "555"}, "note": "x"}""")
        val submitted = json("""{"citizen": {"street": "Kerkstraat 11", "city": "Sittard", "phone": null}, "motivation": "because"}""")

        assertThat(EditedFields.between(caller, submitted)).containsExactly(
            "/citizen/phone",
            "/citizen/street",
            "/motivation",
            "/note",
        )
    }

    @Test
    fun `an array is reported as one value`() {
        assertThat(EditedFields.between(json("""{"lines": [1, 2]}"""), json("""{"lines": [1, 2, 3]}"""))).containsExactly("/lines")
    }

    @Test
    fun `a member replaced by another type is reported at its pointer`() {
        assertThat(EditedFields.between(json("""{"a": {"b": 1}}"""), json("""{"a": "flat"}"""))).containsExactly("/a")
    }

    @Test
    fun `pointer tokens are escaped as RFC 6901 says`() {
        assertThat(EditedFields.between(json("""{}"""), json("""{"a/b": 1, "c~d": 2}"""))).containsExactly("/a~1b", "/c~0d")
    }
}
