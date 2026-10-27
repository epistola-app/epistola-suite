// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import db.migration.core.frozen.DataExampleKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * The 2.0 upgrade keys a 1.x data contract's examples ([DataExampleKeys]) exactly as the contract's
 * v7-to-v8 migration keys a catalog's: derived from the name, in document order, unique within the
 * template, keeping a key an example already has; and stores them in the wire form, without the id
 * 1.x kept beside them.
 */
class DataExampleKeysTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `stored data examples get the keys the contract would give them`() {
        val stored = mapper.readTree(
            """
            [
                {"id": "e1", "name": "Ärger & Co.", "data": {}},
                {"id": "e2", "name": "Standaard brief", "data": {}},
                {"id": "e3", "name": "Standaard brief", "data": {}},
                {"id": "e4", "name": "Kept", "key": "standaard-brief", "data": {}}
            ]
            """,
        )
        val keyed = DataExampleKeys.keyedByName(stored)!!.toList()
        assertThat(keyed.map { it["name"].asString() to it["key"].asString() }).containsExactly(
            "Ärger & Co." to "arger-co",
            "Standaard brief" to "standaard-brief-2",
            "Standaard brief" to "standaard-brief-3",
            "Kept" to "standaard-brief",
        )
        assertThat(keyed.map { it["id"].asString() }).`as`("keying keeps what 1.x kept").containsExactly("e1", "e2", "e3", "e4")
        assertThat(DataExampleKeys.examples(stored).toList().map { it.propertyNames().asSequence().toSet() })
            .`as`("the wire form: name, data and key")
            .allSatisfy { assertThat(it).containsExactlyInAnyOrder("name", "data", "key") }
    }
}
