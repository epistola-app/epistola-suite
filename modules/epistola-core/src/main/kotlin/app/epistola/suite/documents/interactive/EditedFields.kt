// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The JSON Pointers (RFC 6901) where submitted data differs from what the caller sent (contract
 * ADR 0011, `editedFields`): a value changed, added, cleared to null, or removed. Objects are
 * compared member by member; an array is one value, reported at its own pointer when anything in it
 * changed, since an index means little once rows are added or removed.
 */
object EditedFields {
    fun between(callerData: ObjectNode, submitted: ObjectNode): List<String> = mutableListOf<String>().also { diff("", callerData, submitted, it) }

    private fun diff(pointer: String, before: JsonNode?, after: JsonNode?, into: MutableList<String>) {
        if (before is ObjectNode && after is ObjectNode) {
            (before.propertyNames() + after.propertyNames()).distinct().sorted().forEach { name ->
                diff("$pointer/${escape(name)}", before.get(name), after.get(name), into)
            }
        } else if (before != after) {
            into += pointer
        }
    }

    private fun escape(name: String): String = name.replace("~", "~0").replace("/", "~1")
}
