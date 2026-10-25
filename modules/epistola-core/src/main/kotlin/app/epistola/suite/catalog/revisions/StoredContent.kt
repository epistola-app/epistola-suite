// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import tools.jackson.databind.JsonNode

/**
 * The one shape of a stored content of a type stored as JSON (ADR 0033):
 * `{ "resource": <released form>, <setting>: ... }`. `resource` is what a release holds and exports
 * and what the contract fingerprints; beside it go the settings only this suite keeps (a code list's
 * `source`), none for a theme yet. Every reader takes the released form through [resourceOf], which
 * refuses any other shape, so a writer that forgets the envelope fails at once
 * (`StoredContentShapeTest`).
 */
object StoredContent {
    /** The resource types stored as JSON, and so in the envelope. Types still in tables are stored bare. */
    val ENVELOPED_TYPES: Set<String> = setOf("theme", "codeList", "attribute", "stencil")

    /** The released form a stored content holds; a content without a `resource` object is refused. */
    fun resourceOf(payload: JsonNode): JsonNode = payload.get("resource")?.takeIf { it.isObject }
        ?: throw IllegalStateException("A stored content holds its released form under `resource` (ADR 0033); this one has no `resource` object")

    /** The released form of a stored content of [type]: the envelope's `resource`, or the content itself for a type still stored bare. */
    fun releasedForm(type: String, payload: JsonNode): JsonNode = if (type in ENVELOPED_TYPES) resourceOf(payload) else payload

    /** The content's suite-only setting [name] beside its released form, or null. */
    fun settingOf(payload: JsonNode, name: String): JsonNode? = payload.get(name)?.takeUnless { it.isNull }
}
