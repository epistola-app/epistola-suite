// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.migrations.CatalogMigrationContext
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.MigratedManifest
import app.epistola.suite.catalog.outbound.CatalogSourceProperties
import app.epistola.suite.catalog.outbound.OutboundSourceReader
import app.epistola.suite.catalog.outbound.OutboundUrlGuard
import app.epistola.suite.catalog.outbound.OutboundUrlRejectedException
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

@Component
class CatalogClient(
    private val sourceReader: OutboundSourceReader,
    private val schemaMigrator: CatalogSchemaMigrator,
    private val properties: CatalogSourceProperties,
    /** The bundled catalogs as read from the classpath: one per JVM unless a test brings its own. */
    private val classpathCache: ClasspathCatalogCache = ClasspathCatalogCache.SHARED,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val allowHttp: Boolean get() = properties.allowHttp

    private inline fun <reified T : Any> cachedClasspath(key: String, crossinline load: () -> Pair<T, Int>): T = classpathCache.get(key) { load() } as T

    /** The bound, current-shape manifest. Use [fetchMigratedManifest] when you also need to fetch details. */
    fun fetchManifest(url: String, authType: AuthType, credential: String?): CatalogManifest = fetchMigratedManifest(url, authType, credential).manifest

    /**
     * Like [fetchManifest], but also returns the [CatalogMigrationContext] that
     * must be threaded into [fetchResourceDetail] for every detail of the same
     * catalog (the catalog's source version + migrated manifest tree).
     */
    fun fetchMigratedManifest(url: String, authType: AuthType, credential: String?): MigratedManifest {
        validateUrl(url, ALL_SCHEMES)
        if (url.startsWith("classpath:")) {
            return cachedClasspath("manifest:$url") { loadManifest(url, authType, credential) }
        }
        return loadManifest(url, authType, credential).first
    }

    /** The bound manifest plus the size of the bytes it came from (the cache weight). */
    private fun loadManifest(url: String, authType: AuthType, credential: String?): Pair<MigratedManifest, Int> {
        logger.debug("Fetching catalog manifest from {}", url)
        // Fetch raw bytes and route through the schema migrator (version gate +
        // wire-format upgrade chain) before binding. This is the single remote
        // chokepoint, so every consumer — install, browse, upgrade-check,
        // fingerprint — sees a current-shape manifest.
        val bytes = sourceReader.readSource(url, authType.headers(credential), allowHttp)
        // A classpath catalog is one Epistola ships; a remote one from before wire v8 has no namespace
        // this installation could give it, so it is refused until its publisher moves to v8.
        val namespace = CatalogIdentityProperties.BUNDLED_NAMESPACE.takeIf { url.startsWith("classpath:") }
        return schemaMigrator.migrateAndBindManifest(bytes, namespace) to bytes.size
    }

    /**
     * Fetch one resource detail and upgrade it to the current catalog wire shape
     * before binding. [type] is the resource type discriminator (from the manifest
     * entry); [catalog] is the context from [fetchMigratedManifest]. The migrator
     * rejects a detail whose version differs from the catalog's, verifies
     * `resource.type` matches [type], and exposes the manifest to cross-part steps.
     */
    fun fetchResourceDetail(
        type: String,
        detailUrl: String,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
        catalog: CatalogMigrationContext,
    ): ResourceDetail {
        val resolvedUrl = resolveDetailUrl(detailUrl, manifestUrl)
        validateUrl(resolvedUrl, ALL_SCHEMES)
        if (resolvedUrl.startsWith("classpath:")) {
            // The migration context comes from the manifest, so the manifest URL is part of the key.
            return cachedClasspath("detail:$type\n$manifestUrl\n$resolvedUrl") {
                loadResourceDetail(type, resolvedUrl, manifestUrl, authType, credential, catalog)
            }
        }
        return loadResourceDetail(type, resolvedUrl, manifestUrl, authType, credential, catalog).first
    }

    private fun loadResourceDetail(
        type: String,
        resolvedUrl: String,
        manifestUrl: String,
        authType: AuthType,
        credential: String?,
        catalog: CatalogMigrationContext,
    ): Pair<ResourceDetail, Int> {
        logger.debug("Fetching resource detail from {}", resolvedUrl)
        val bytes = sourceReader.readDerived(resolvedUrl, manifestUrl, authType.headers(credential), allowHttp)
        return schemaMigrator.migrateAndBindResourceDetail(type, bytes, catalog) to bytes.size
    }

    /**
     * The bytes behind a content (or detail) URL the manifest at [manifestUrl] named. Checked like
     * every other read: a manifest cannot name content on another scheme, outside the file roots or
     * on a non-public address — that is what keeps an installed image from being a copy of a local
     * file or an internal endpoint.
     */
    fun fetchBinaryContent(contentUrl: String, manifestUrl: String, authType: AuthType, credential: String?): ByteArray {
        val resolvedUrl = resolveContentUrl(contentUrl, manifestUrl)
        if (resolvedUrl.startsWith("classpath:")) {
            // Shared, not copied: every tenant installs the bundled catalogs, and a copy per install
            // held the system fonts (14 MB) again for each tenant being created. Callers only read it.
            return cachedClasspath<ByteArray>("binary:$manifestUrl\n$resolvedUrl") {
                loadBinary(resolvedUrl, manifestUrl, authType, credential).let { it to it.size }
            }
        }
        return loadBinary(resolvedUrl, manifestUrl, authType, credential)
    }

    private fun loadBinary(resolvedUrl: String, manifestUrl: String, authType: AuthType, credential: String?): ByteArray {
        logger.debug("Fetching binary content from {}", resolvedUrl)
        return sourceReader.readDerived(resolvedUrl, manifestUrl, authType.headers(credential), allowHttp)
    }

    companion object {
        /** A leading `scheme:` — anything that makes a path an absolute URL. */
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        private val ALL_SCHEMES = setOf("https", "http", "file", "classpath")

        /**
         * The shape of a catalog URL: a `.json` file on one of [allowedSchemes]. Whether that URL may
         * actually be read — file roots, addresses, `classpath:` — is [OutboundUrlGuard]'s decision.
         */
        fun validateUrl(url: String, allowedSchemes: Set<String> = setOf("https", "file", "classpath")) {
            require(url.substringAfterLast(".").equals("json", ignoreCase = true)) {
                "Catalog URLs must point to .json files"
            }
            val scheme = url.substringBefore(":")
            require(scheme in allowedSchemes) {
                "Unsupported URL scheme: $scheme. Allowed: $allowedSchemes"
            }
            if (url.startsWith("file:")) {
                val uri = URI.create(url)
                val rawPath = uri.path ?: ""
                require(!rawPath.contains("..")) { "Path traversal not allowed" }
            }
        }

        /** A binary's [contentUrl], relative to the manifest at [manifestUrl]; see [resolveRelative]. */
        fun resolveContentUrl(contentUrl: String, manifestUrl: String): String = resolveRelative(contentUrl, manifestUrl)

        /** A resource's [detailUrl], relative to the manifest at [manifestUrl]; see [resolveRelative]. */
        fun resolveDetailUrl(detailUrl: String, manifestUrl: String): String = resolveRelative(detailUrl, manifestUrl)

        /**
         * Resolves a path a manifest names against the manifest's own location.
         *
         * Only a relative path below the manifest's directory is accepted, which is the only form a
         * catalog is written in (`./resources/…`, `bin/<hash>`). An absolute URL, a protocol-relative
         * `//host/…`, a root-relative `/…`, a `..` segment (encoded or not) or a backslash is
         * refused: a manifest must never send a fetch — or the catalog's credentials — to another
         * host, another scheme, or outside its own tree.
         */
        fun resolveRelative(path: String, manifestUrl: String): String {
            val segments = path.split('/')
            val refused = path.isBlank() ||
                path.startsWith("/") ||
                '\\' in path ||
                SCHEME.containsMatchIn(path) ||
                segments.any { it.replace("%2e", ".", ignoreCase = true) == ".." }
            if (refused) {
                throw OutboundUrlRejectedException("A catalog may only name paths relative to its manifest, not '$path'")
            }
            val resolved = if (manifestUrl.startsWith("classpath:")) {
                val baseDir = manifestUrl.substringAfter("classpath:").substringBeforeLast("/")
                val relative = segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
                "classpath:$baseDir/$relative"
            } else {
                URI.create(manifestUrl).resolve(path).toString()
            }
            OutboundUrlGuard.requireBelowManifest(resolved, manifestUrl)
            return resolved
        }
    }
}

/**
 * The bundled catalogs as read from the classpath: manifests, resource details and binaries.
 *
 * The classpath cannot change while the JVM runs, yet the same manifest, details and binaries were
 * read, schema-migrated and hashed again for every tenant that installs them, every catalogs-page
 * render and every boot. Cached like FontByteCache: one Caffeine cache, weighed by the raw bytes each
 * entry was loaded from, with an explicit ceiling (the system catalog's fonts are 14 MB and every other
 * bundled catalog is under half a megabyte; the ceiling is the bound stated out loud, not something that
 * is expected to evict). No TTL: classpath content is immutable. Only `classpath:` sources are cached;
 * `file:` and HTTP sources can change underneath a running process. What it holds is shared across
 * tenants and threads: callers read it and map it into their own structures; nothing writes into a
 * fetched object, a fetched byte array included.
 *
 * One per JVM ([SHARED]), not one per [CatalogClient]: Spring makes a client per application context,
 * and a test JVM holds a couple of dozen contexts, each of which held the system fonts again.
 */
class ClasspathCatalogCache(maxBytes: Long = MAX_BYTES) {
    private class Entry(val value: Any, val weight: Int)

    private val cache = Caffeine.newBuilder()
        .maximumWeight(maxBytes)
        .weigher<String, Entry> { _, entry -> entry.weight }
        .build<String, Entry>()

    /** The value cached under [key], loading it as (value, the size of the bytes it came from) when absent. */
    fun get(key: String, load: () -> Pair<Any, Int>): Any = cache.get(key) { load().let { (value, weight) -> Entry(value, weight) } }.value

    companion object {
        /** Ceiling, in bytes of source content. Every bundled catalog together is about 15 MiB, the system fonts nearly all of it. */
        const val MAX_BYTES: Long = 32L * 1024 * 1024

        /** The JVM's: what every [CatalogClient] uses unless it is given its own. */
        val SHARED = ClasspathCatalogCache()
    }
}
