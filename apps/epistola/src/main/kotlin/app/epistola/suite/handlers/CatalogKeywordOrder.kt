// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

/**
 * The order catalog keywords are shown in, which is the order the catalog protocol already puts
 * them in.
 *
 * Keywords are a `Set<String>` on [app.epistola.suite.catalog.CatalogMetadata], so locally the
 * order is whatever the stored JSON array happened to keep — the author's typing order. It *looks*
 * sorted often enough that the manual test report took it for a broken sort, having noticed that
 * keywords starting with a digit came last, which no locale sorts that way. That was the tell: not
 * a broken sort, no sort at all.
 *
 * The protocol does sort them, in natural order — `CatalogInfo` holds them in a `TreeSet` and
 * `CatalogCanonicalizer` writes `keywords.sorted()` — so a catalog that has been published,
 * installed or exported comes back ordered, while a purely local one does not. Sorting here with
 * anything else would mean the page and the catalog's own manifest listing the same keywords
 * differently, so this deliberately matches: plain natural order, no locale, no case folding.
 *
 * Natural order already puts digits before letters, so that needs no special case. It also puts
 * every capitalised keyword before every lowercase one, which reads oddly — moot once the contract
 * constrains keywords to lowercase, and until then not worth diverging from the wire over.
 *
 * Applied to the surfaces that display keywords: the catalog page and the edit dialog, so opening
 * the dialog never reshuffles what is on screen. Data surfaces — REST, MCP, export — keep passing
 * the set through as they get it, because the protocol has already decided.
 */
val CATALOG_KEYWORD_ORDER: Comparator<String> = naturalOrder()

/** The keywords of a catalog, in the order they are shown. */
fun Collection<String>.inDisplayOrder(): List<String> = sortedWith(CATALOG_KEYWORD_ORDER)
