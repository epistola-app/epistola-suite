// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

/**
 * The order catalog keywords are shown in.
 *
 * Keywords are a `Set<String>` on [app.epistola.suite.catalog.CatalogMetadata] — the domain has no
 * order and should not acquire one. Whatever order a reader sees today is whatever the JSON array
 * happened to keep, which is the author's typing order; it *looks* sorted often enough that the
 * manual test report took it for a broken sort, and noticed that keywords starting with a digit
 * came last, which no locale sorts that way.
 *
 * Rather than make the stored order meaningful, the order is decided here, once, for the surfaces
 * that display it. Both the catalog page and the edit dialog use it, so the badges and the textarea
 * agree and opening the dialog never reshuffles what is on screen. Data surfaces — REST, MCP,
 * export — keep passing the set through unordered, because presentation is not part of the
 * contract.
 *
 * Digits before letters, then case-insensitively alphabetical, with the raw string as a final
 * tiebreak so `Alpha` and `alpha` cannot swap places between two renders of the same set. Keyword
 * uniqueness is case-sensitive (`CatalogHandler.parseMetadataKeywords`), so both can be present.
 */
val CATALOG_KEYWORD_ORDER: Comparator<String> = compareBy(
    { if (it.firstOrNull()?.isDigit() == true) 0 else 1 },
    { it.lowercase() },
    { it },
)

/** The keywords of a catalog, in the order they are shown. */
fun Collection<String>.inDisplayOrder(): List<String> = sortedWith(CATALOG_KEYWORD_ORDER)
