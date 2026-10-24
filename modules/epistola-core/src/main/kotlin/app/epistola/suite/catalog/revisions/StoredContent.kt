// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import tools.jackson.databind.JsonNode

/**
 * The shapes a stored content has (ADR 0033). A resource whose suite keeps settings beside its
 * released form is stored as `{ "resource": <released form>, <setting>: ... }` (a code list, its
 * `source`); one without is stored as its released form alone (a theme, and a code list a release
 * kept before code lists moved). Every reader goes through [resourceOf], so moving a type to the
 * envelope changes its writer only.
 */
object StoredContent {
    /** The released form a stored content holds. */
    fun resourceOf(payload: JsonNode): JsonNode = payload.get("resource")?.takeIf { it.isObject && !payload.has("type") } ?: payload

    /** The content's suite-only setting [name] beside its released form, or null. */
    fun settingOf(payload: JsonNode, name: String): JsonNode? = if (resourceOf(payload) === payload) null else payload.get(name)?.takeUnless { it.isNull }
}
