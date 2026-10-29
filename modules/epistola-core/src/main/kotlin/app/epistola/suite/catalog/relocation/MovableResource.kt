// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeKey

/**
 * The resource types relocation supports, and what a key of each may be.
 *
 * Every type is stored alike (ADR 0033) and its references are indexed, so moving one is the same
 * for all ([ReferenceRepointer], ADR 0042): what is particular is only the key a type can hold, and
 * whether its key is a name a rename may change.
 */
enum class MovableResource(
    val type: CatalogResourceType,
    /**
     * Why a key cannot be held by this type, or null when it can -- the same rule its create command
     * enforces through the key's value class, with that class's own message. A relocation that
     * changes the key is checked against it in the preview, so an unusable key is a blocker rather
     * than a database error after approval.
     */
    val keyProblem: (String) -> String?,
    /**
     * Whether a relocation may change this type's key.
     *
     * False where the key is a generated identifier rather than a name. Renaming one is not a
     * rename in any useful sense, and it breaks the references that resolve by that identifier
     * alone -- an unqualified image reference carries no catalog, only the key.
     */
    val renameable: Boolean = true,
) {
    STENCIL(CatalogResourceType.STENCIL, { runCatching { StencilKey.of(it) }.exceptionOrNull()?.message }),
    ATTRIBUTE(CatalogResourceType.ATTRIBUTE, { runCatching { AttributeKey.of(it) }.exceptionOrNull()?.message }),
    TEMPLATE(CatalogResourceType.TEMPLATE, { runCatching { TemplateKey.of(it) }.exceptionOrNull()?.message }),
    CODE_LIST(CatalogResourceType.CODE_LIST, { runCatching { CodeListKey.of(it) }.exceptionOrNull()?.message }),

    /**
     * Resolved tenant-wide when unqualified: a reference naming no catalog means the same wherever
     * its holder lives, so only one naming the catalog the image leaves is re-pointed.
     */
    ASSET(CatalogResourceType.IMAGE, { runCatching { AssetKey.of(it) }.exceptionOrNull()?.message }, renameable = false),
    FONT(CatalogResourceType.FONT, { runCatching { FontKey.of(it) }.exceptionOrNull()?.message }),
    THEME(CatalogResourceType.THEME, { runCatching { ThemeKey.of(it) }.exceptionOrNull()?.message }),
    ;

    companion object {
        fun of(type: CatalogResourceType): MovableResource? = entries.firstOrNull { it.type == type }
    }
}
