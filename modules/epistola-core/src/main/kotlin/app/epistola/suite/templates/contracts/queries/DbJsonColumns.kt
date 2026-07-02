package app.epistola.suite.templates.contracts.queries

import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Parses a database-owned JSON(B) column that must contain an array of strings.
 *
 * These values are produced by our own SQL (`jsonb_agg`) or written by our own
 * commands, so a parse failure is an invariant violation (corrupt row, bug in a
 * write path). It must fail the query loudly: silently degrading to an empty list
 * reads as "compatible" / "not active in any environment" to the contract-publish
 * flow and can green-light a breaking publish.
 */
internal fun ObjectMapper.readStringArrayColumn(json: String, description: String): List<String> = try {
    readValue(json, typeFactory.constructCollectionType(List::class.java, String::class.java))
} catch (e: JacksonException) {
    throw IllegalStateException("Corrupt database value: $description is not a JSON string array", e)
}
