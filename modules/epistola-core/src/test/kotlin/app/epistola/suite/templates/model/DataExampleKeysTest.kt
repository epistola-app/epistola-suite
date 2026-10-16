// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

@Tag("unit")
class DataExampleKeysTest {
    private val mapper = JsonMapper.builder().build()

    private fun example(name: String, key: String? = null) = DataExample(id = name, name = name, data = mapper.createObjectNode(), key = key)

    @Test
    fun `a list of examples always has keys, derived in order and unique, keeping existing ones`() {
        val examples = DataExamples(listOf(example("Standaard brief"), example("Standaard brief"), example("Kept", key = "standaard-brief")))

        assertThat(examples.map { it.key }).containsExactly("standaard-brief-2", "standaard-brief-3", "standaard-brief")
    }

    @Test
    fun `keys are stored with the examples, so a renamed example keeps its key`() {
        val stored = mapper.writeValueAsString(DataExamples(listOf(example("Invoice"))))
        val renamed = mapper.readValue(stored, DataExamples::class.java).map { it.copy(name = "Reminder") }

        assertThat(DataExamples(renamed).single().key).isEqualTo("invoice")
    }
}
