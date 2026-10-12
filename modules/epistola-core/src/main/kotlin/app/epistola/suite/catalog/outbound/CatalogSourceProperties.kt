// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where catalog and code-list sources may be read from.
 *
 * Both features fetch a URL somebody typed in, and the content of a fetched catalog names more URLs
 * of its own. Everything that decides which of those may be read lives here and is enforced by
 * [OutboundUrlGuard]. Code lists share these settings: they are catalog content, and two sets of
 * roots or host rules for one installation would only drift.
 *
 * ```yaml
 * epistola:
 *   catalog:
 *     allow-http: false
 *     file-roots: []
 *     outbound:
 *       allow-private-networks: false
 *       allowed-hosts: []
 *       max-redirects: 3
 * ```
 */
@ConfigurationProperties(prefix = "epistola.catalog")
data class CatalogSourceProperties(
    /** Permits plaintext `http://` catalog sources. Code lists have their own `epistola.codelists.allow-http`. */
    val allowHttp: Boolean = false,
    /**
     * Directories a `file:` source may be read from. Empty by default, which turns `file:` sources
     * off. A path is accepted only when its canonical form (symbolic links resolved) lies inside the
     * canonical form of one of these directories.
     */
    val fileRoots: List<String> = emptyList(),
    val outbound: Outbound = Outbound(),
) {
    data class Outbound(
        /**
         * Permits sources on loopback, private (RFC 1918), carrier-grade NAT, benchmarking and
         * unique-local IPv6 addresses, for an installation whose catalogs live on an intranet.
         * Link-local addresses (including cloud metadata endpoints), unspecified, multicast,
         * broadcast and documentation addresses stay refused regardless.
         */
        val allowPrivateNetworks: Boolean = false,
        /**
         * Host names that may resolve to a private address without opening
         * [allowPrivateNetworks] for every host. Matched exactly, case-insensitively.
         */
        val allowedHosts: List<String> = emptyList(),
        /** How many redirects one fetch follows, each re-checked as if it were the first request. */
        val maxRedirects: Int = 3,
    )
}
