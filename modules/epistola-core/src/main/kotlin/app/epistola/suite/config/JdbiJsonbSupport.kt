package app.epistola.suite.config

import org.jdbi.v3.core.statement.Query
import org.jdbi.v3.core.statement.Update
import tools.jackson.databind.ObjectMapper

/**
 * Extension functions for JSONB binding and serialization.
 *
 * These helpers eliminate the need to manually call `objectMapper.writeValueAsString()`
 * for every JSONB parameter, improving code clarity.
 *
 * Before:
 * ```kotlin
 * handle.createUpdate(
 *     "INSERT INTO themes (document_styles) VALUES (:styles::jsonb)"
 * )
 *     .bind("styles", objectMapper.writeValueAsString(documentStyles))
 *     .execute()
 * ```
 *
 * After:
 * ```kotlin
 * handle.createUpdate(
 *     "INSERT INTO themes (document_styles) VALUES (:styles::jsonb)"
 * )
 *     .bindJsonb("styles", documentStyles, objectMapper)
 *     .execute()
 * ```
 */

/**
 * Bind a value as JSONB by automatically serializing it.
 *
 * Useful for UPDATE/INSERT statements with JSONB columns.
 *
 * @param name Parameter name (without ::jsonb suffix - add that in SQL)
 * @param value The object to serialize (can be null)
 * @param objectMapper Jackson ObjectMapper for serialization
 * @return This Update for method chaining
 */
fun Update.bindJsonb(name: String, value: Any?, objectMapper: ObjectMapper): Update =
    bind(name, value?.let { objectMapper.writeValueAsString(it) })

/**
 * Bind a value as JSONB for a query parameter.
 *
 * @param name Parameter name (without ::jsonb suffix - add that in SQL)
 * @param value The object to serialize (can be null)
 * @param objectMapper Jackson ObjectMapper for serialization
 * @return This Query for method chaining
 */
fun Query.bindJsonb(name: String, value: Any?, objectMapper: ObjectMapper): Query =
    bind(name, value?.let { objectMapper.writeValueAsString(it) })
