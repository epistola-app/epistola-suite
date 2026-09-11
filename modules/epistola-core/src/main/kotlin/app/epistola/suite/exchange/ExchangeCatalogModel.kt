// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import java.time.OffsetDateTime

/**
 * What Exchange says about a catalog it hosts, in Suite's own terms.
 *
 * Exchange's vocabularies — visibility, scan state, release availability — are carried as
 * [String] rather than enums for the same reason [CatalogPublicationStatus.fromRemote] keeps
 * submission state a string: they are Exchange's words, and a value it adds later must not become
 * a Suite failure. Note this only limits the damage. The generated client binds them to closed
 * enums on the way in, so an unrecognised value fails the *whole* page before anything here sees
 * it; callers treat that as a failed check rather than an error page, and the real fix is a wire
 * model that tolerates unknown members.
 */
data class ExchangeCatalogSummary(
    val namespace: String,
    val catalogKey: String,
    val name: String,
    val description: String?,
    val organizationName: String,
    val visibility: String,
    /** Exchange's own answer for "newest release anyone may install", already excluding blocked and withdrawn ones. */
    val latestVersion: String?,
) {
    /** How this catalog is addressed as a subscribed catalog's source. See [ExchangeSourceUri]. */
    val sourceUri: String get() = ExchangeSourceUri.of(namespace, catalogKey)
}

/** One page of catalogs, with the cursor to ask for the next — never the URL Exchange returned. */
data class ExchangeCatalogPage(
    val items: List<ExchangeCatalogSummary>,
    val nextCursor: String?,
)

data class ExchangeCatalogRelease(
    val namespace: String,
    val catalogKey: String,
    val version: String,
    val fingerprint: String?,
    val sha256: String,
    val sizeBytes: Long,
    val scanState: String,
    val availability: String,
    val publishedAt: OffsetDateTime,
) {
    /**
     * Whether this release may be installed.
     *
     * Exchange already hides blocked and withdrawn releases from everyone except the installation
     * that publishes the catalog — which, for a tenant that both publishes and installs, is us. So
     * this is checked here rather than assumed from the fact that Exchange returned it.
     */
    val installable: Boolean get() = availability == AVAILABLE

    companion object {
        const val AVAILABLE = "AVAILABLE"
        const val WITHDRAWN = "WITHDRAWN"
        const val BLOCKED = "BLOCKED"
    }
}

/**
 * The `exchange:` source URI, which is how a catalog installed from Exchange records where it came
 * from — `exchange:acme/invoices`.
 *
 * A URI rather than a pair of columns so that `catalogs.source_url` stays the one answer to "where
 * did this come from" for every kind of subscribed catalog. A fourth kind is then a new scheme
 * rather than a migration, and the scheme is what decides which
 * [app.epistola.suite.catalog.CatalogUpstreamProbe] can answer for it.
 *
 * Deliberately not a `.json` URL on a known scheme: `CatalogClient.validateUrl` accepts neither, so
 * an `exchange:` URI can never be mistaken for a manifest to fetch, and pasting one into the
 * subscribe-by-URL dialog is refused without needing a guard of its own.
 */
object ExchangeSourceUri {
    const val SCHEME = "exchange"

    fun of(namespace: String, catalogKey: String): String = "$SCHEME:$namespace/$catalogKey"

    fun matches(sourceUrl: String?): Boolean = sourceUrl != null && sourceUrl.startsWith("$SCHEME:")

    /** The coordinates in [sourceUrl], or null when it is not an Exchange source or is malformed. */
    fun parse(sourceUrl: String?): ExchangeCatalogCoordinates? {
        if (!matches(sourceUrl)) return null
        val path = sourceUrl!!.substringAfter("$SCHEME:")
        val namespace = path.substringBefore('/', missingDelimiterValue = "")
        val catalogKey = path.substringAfter('/', missingDelimiterValue = "")
        if (namespace.isBlank() || catalogKey.isBlank() || catalogKey.contains('/')) return null
        return ExchangeCatalogCoordinates(namespace, catalogKey)
    }
}

data class ExchangeCatalogCoordinates(val namespace: String, val catalogKey: String) {
    override fun toString(): String = "$namespace/$catalogKey"
}
