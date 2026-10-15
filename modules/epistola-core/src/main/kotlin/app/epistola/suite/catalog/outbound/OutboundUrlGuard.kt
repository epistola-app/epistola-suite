// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path

/** A catalog or code-list fetch that did not complete. The message is safe to show to the user. */
open class OutboundFetchException(override val message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A source, redirect or derived URL that [OutboundUrlGuard] refuses to read. */
class OutboundUrlRejectedException(message: String) : OutboundFetchException(message)

/** Resolves a host name to every address it has. Replaceable so tests need no DNS. */
fun interface HostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): Array<InetAddress>

    companion object {
        val SYSTEM = HostResolver { host -> InetAddress.getAllByName(host) }
    }
}

/**
 * The one decision about which URLs a catalog or code-list fetch may read.
 *
 * Every outbound read — a catalog manifest, a resource detail, an image or font binary, a code
 * list, and every redirect hop between them — passes through here, at input time (so a bad URL is
 * refused with a validation error rather than stored) and again at fetch time (because a stored
 * URL, or one a fetched manifest names, was never typed by anyone this installation trusts).
 *
 * - `https:` is always allowed in shape, `http:` only where the caller's `allow-http` flag says so.
 *   Every address the host resolves to must be public; see [AddressClassification]. The connection
 *   itself goes through [resolveForConnection], so the addresses that were checked are the
 *   addresses that are dialled — a host cannot answer the check with one address and the
 *   connection with another.
 * - `file:` is off unless `epistola.catalog.file-roots` names directories, and then only reads a
 *   regular file whose canonical path lies inside one of them.
 * - `classpath:` reads only under [BUNDLED_CATALOG_ROOT], which is where Epistola ships its own
 *   catalogs. The classpath holds far more than catalogs — configuration, every library's resources —
 *   so being on it does not make a file importable; being shipped as a catalog does.
 * - A URL derived from a manifest stays with it: same scheme and origin, inside the manifest's
 *   directory ([requireBelowManifest]). Catalogs name their resources by relative path only.
 */
@Component
class OutboundUrlGuard(
    private val properties: CatalogSourceProperties,
    private val resolver: HostResolver,
) {
    @Autowired
    constructor(properties: CatalogSourceProperties) : this(properties, HostResolver.SYSTEM)

    private val allowedHosts = properties.outbound.allowedHosts.map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.toSet()

    /**
     * Hosts of a proxy configured through the JVM's standard proxy properties. The fetcher routes
     * through such a proxy, so its address is resolved for the connection instead of the target's;
     * an operator configured it, so it is exempt from the address rules. The target is still
     * checked before every request.
     */
    private val proxyHosts = listOf("https.proxyHost", "http.proxyHost")
        .mapNotNull { System.getProperty(it)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
        .toSet()

    val maxRedirects: Int get() = properties.outbound.maxRedirects

    /**
     * A catalog or code-list source: one a user is about to store, or a stored one about to be
     * fetched. The same rules apply to both.
     */
    fun checkSource(url: String, allowHttp: Boolean) {
        when (val scheme = schemeOf(url)) {
            "https" -> checkNetworkUrl(url)
            "http" -> if (allowHttp) checkNetworkUrl(url) else reject("Plain http:// sources are not allowed; use https://")
            "file" -> requireFileUnderRoots(url)
            "classpath" -> requireBundledClasspath(url)
            else -> reject("Unsupported URL scheme '$scheme'")
        }
    }

    /** A detail or content URL a manifest at [parentUrl] named, after relative resolution. */
    fun checkDerived(url: String, parentUrl: String, allowHttp: Boolean) {
        val parentScheme = schemeOf(parentUrl)
        val scheme = schemeOf(url)
        val permitted = when (parentScheme) {
            "https" -> setOf("https")
            "http" -> setOf("http", "https")
            "file" -> setOf("file")
            "classpath" -> setOf("classpath")
            else -> emptySet()
        }
        if (scheme !in permitted) {
            reject("A catalog read from a $parentScheme: source may not reference $scheme: content")
        }
        requireBelowManifest(url, parentUrl)
        checkSource(url, allowHttp)
    }

    /** A redirect from [from] to [to]. Never to another scheme family, and never from https down to http. */
    fun checkRedirect(from: URI, to: URI, allowHttp: Boolean) {
        val scheme = to.scheme
        val permitted = when (from.scheme) {
            "https" -> setOf("https")
            "http" -> setOf("http", "https")
            else -> emptySet()
        }
        if (scheme !in permitted) reject("Redirect to a $scheme: URL is not followed")
        checkSource(to.toString(), allowHttp)
    }

    /**
     * The `file:` URL as a canonical path inside a configured root. Read that path, not the URL: it
     * is what was checked.
     */
    fun requireFileUnderRoots(url: String): Path {
        val roots = properties.fileRoots.filter { it.isNotBlank() }
        if (roots.isEmpty()) {
            reject("file: sources are disabled; list the directory in epistola.catalog.file-roots to allow it")
        }
        val uri = parse(url)
        val rawPath = uri.path
        if (uri.isOpaque || uri.rawAuthority != null || rawPath.isNullOrEmpty()) {
            reject("A file: source must be an absolute file:/// URL")
        }
        if (rawPath.split('/').any { it == ".." }) reject("Path traversal is not allowed")
        val real = try {
            Path.of(uri).toRealPath()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val inside = real != null && Files.isRegularFile(real) && roots.any { root ->
            val canonicalRoot = try {
                Path.of(root).toRealPath()
            } catch (_: IOException) {
                null
            }
            canonicalRoot != null && real.startsWith(canonicalRoot)
        }
        // One message for "missing" and "outside": anything finer would tell a caller which paths exist.
        if (!inside) reject("The file does not exist or is not inside a directory listed in epistola.catalog.file-roots")
        return real
    }

    /**
     * The addresses a connection to [host] may use: all of them checked, none of them refused. The
     * HTTP client dials only what this returns.
     */
    @Throws(UnknownHostException::class)
    fun resolveForConnection(host: String): Array<InetAddress> {
        val normalized = normalizeHost(host)
        if (normalized in proxyHosts) return resolver.resolve(host)
        literalOrNull(normalized)?.let { return arrayOf(it) }
        val addresses = resolver.resolve(normalized)
        addresses.forEach { enforce(normalized, AddressClassification.classify(it)) }
        return addresses
    }

    private fun checkNetworkUrl(url: String) {
        val uri = parse(url)
        val host = uri.host?.let(::normalizeHost)
        if (host.isNullOrEmpty()) reject("The URL must name a host")
        literalOrNull(host)?.let { return }
        // Unresolvable now is not refused here: the fetch will fail on its own, and the connection
        // re-resolves and re-checks through resolveForConnection anyway.
        val addresses = try {
            resolver.resolve(host)
        } catch (_: UnknownHostException) {
            return
        }
        addresses.forEach { enforce(host, AddressClassification.classify(it)) }
    }

    /**
     * A host written as an address: checked here and returned, or null for a name. Loopback names
     * (`localhost`, `*.localhost`) count as addresses, because they never mean anything else.
     */
    private fun literalOrNull(host: String): InetAddress? {
        if (host == "localhost" || host.endsWith(".localhost")) {
            enforce(host, AddressClass.PRIVATE)
            return InetAddress.getLoopbackAddress()
        }
        if (':' in host) {
            val address = try {
                InetAddress.ofLiteral(host.substringBefore('%'))
            } catch (_: IllegalArgumentException) {
                reject("The URL host is not a valid address")
            }
            enforce(host, AddressClassification.classify(address))
            return address
        }
        if (NUMERIC_HOST.matches(host)) {
            // Only the canonical dotted quad. 2130706433, 0x7f.1 and 0177.0.0.1 are all 127.0.0.1 to
            // some parser somewhere; refusing them leaves no room for two parsers to disagree.
            if (!CANONICAL_V4.matches(host) || host.split('.').any { it.length > 1 && it.startsWith('0') }) {
                reject("The URL host is a numeric address in a non-standard form")
            }
            val address = InetAddress.ofLiteral(host)
            enforce(host, AddressClassification.classify(address))
            return address
        }
        return null
    }

    private fun enforce(host: String, addressClass: AddressClass?) {
        when (addressClass) {
            null -> Unit

            AddressClass.FORBIDDEN -> reject("The host '$host' is not an address Epistola fetches from")

            AddressClass.PRIVATE -> if (!properties.outbound.allowPrivateNetworks && host !in allowedHosts) {
                reject(
                    "The host '$host' is on a private network; allow it with " +
                        "epistola.catalog.outbound.allowed-hosts or allow-private-networks",
                )
            }
        }
    }

    private fun requireBundledClasspath(url: String) {
        val path = url.removePrefix("classpath:")
        val segments = path.split('/')
        val clean = path.startsWith(BUNDLED_CATALOG_ROOT) &&
            segments.none { it.isEmpty() || it == "." || it == ".." } &&
            '\\' !in path &&
            '%' !in path
        if (!clean) reject("Only catalogs bundled with Epistola may be read from the classpath")
    }

    private fun parse(url: String): URI = try {
        URI(url)
    } catch (_: URISyntaxException) {
        reject("The URL is not valid")
    }

    private fun normalizeHost(host: String): String = host.removeSurrounding("[", "]").lowercase().trimEnd('.')

    companion object {
        /**
         * A URL derived from the manifest at [manifestUrl] must stay with it: same scheme and origin,
         * inside the manifest's directory. A manifest therefore cannot send a fetch, or the catalog's
         * credentials, to another host or outside its own tree.
         */
        fun requireBelowManifest(url: String, manifestUrl: String) {
            val inside = if (manifestUrl.startsWith("classpath:")) {
                val path = url.removePrefix("classpath:")
                url.startsWith("classpath:") &&
                    path.startsWith(manifestUrl.substringAfter("classpath:").substringBeforeLast("/") + "/") &&
                    path.split('/').none { it == ".." || it.equals("%2e%2e", ignoreCase = true) }
            } else {
                val base = uriOrReject(manifestUrl)
                val target = uriOrReject(url)
                // getPath() is decoded, so an encoded `%2e%2e` shows up here as `..`.
                val targetPath = target.path.orEmpty()
                val baseDir = base.normalize().path.orEmpty().substringBeforeLast("/") + "/"
                target.scheme == base.scheme &&
                    target.rawAuthority == base.rawAuthority &&
                    targetPath.split('/').none { it == ".." } &&
                    targetPath.startsWith(baseDir)
            }
            if (!inside) reject("A catalog may only name paths relative to its manifest, not '$url'")
        }

        private fun uriOrReject(url: String): URI = try {
            URI(url)
        } catch (_: URISyntaxException) {
            reject("Not a valid URL: $url")
        }

        /** Where Epistola ships its bundled catalogs (`system`, and `demo` in the demo app). */
        const val BUNDLED_CATALOG_ROOT = "epistola/catalogs/"

        private val NUMERIC_HOST = Regex("^(0x[0-9a-f]*|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]*))*$")
        private val CANONICAL_V4 = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")

        /** The part before the first colon, compared exactly: `HTTPS:` is not a scheme we fetch. */
        fun schemeOf(url: String): String = url.substringBefore(':', missingDelimiterValue = "")

        private fun reject(message: String): Nothing = throw OutboundUrlRejectedException(message)
    }
}
