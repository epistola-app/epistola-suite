// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.common.ids.AttributeId

/**
 * SQL for "which variants refer to this attribute definition", shared by every check that must not
 * remove a definition, or values from it, from under a variant (#1022).
 *
 * A variant's `attributes` map names a definition either way `validateAttributes` accepts:
 *  - **qualified** — `"<catalogKey>.<slug>"`, e.g. `acme.brand`: exactly this definition;
 *  - **bare** — `"<slug>"`: resolved tenant-wide, so it may mean this definition.
 *
 * Both forms count, and from a template in **any** of the tenant's catalogs: a template in one
 * catalog may use an attribute defined in another. When several catalogs define the same slug a
 * bare key is ambiguous, and counting it for each of them is deliberately conservative — refusing
 * a delete is recoverable, orphaning a variant's attribute is not.
 *
 * The fragments read the `attributes` column of the variant alias given and expect the
 * [qualifiedKey] and [bareKey] values bound under [QUALIFIED_PARAM] and [BARE_PARAM].
 */
object AttributeReferences {
    const val QUALIFIED_PARAM = "attributeQualifiedKey"
    const val BARE_PARAM = "attributeBareKey"

    fun qualifiedKey(id: AttributeId): String = "${id.catalogKey.value}.${id.key.value}"

    fun bareKey(id: AttributeId): String = id.key.value

    /** True when the variant names the definition in either form. */
    fun uses(variantAlias: String): String = "(jsonb_exists($variantAlias.attributes, :$QUALIFIED_PARAM) OR jsonb_exists($variantAlias.attributes, :$BARE_PARAM))"

    /** True when the variant names the definition, in either form, with the value bound as [valueParam]. */
    fun usesValue(variantAlias: String, valueParam: String): String = "($variantAlias.attributes ->> :$QUALIFIED_PARAM = :$valueParam OR $variantAlias.attributes ->> :$BARE_PARAM = :$valueParam)"
}
