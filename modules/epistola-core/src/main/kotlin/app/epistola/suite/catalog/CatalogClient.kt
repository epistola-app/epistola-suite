// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.migrations.CatalogMigrationContext
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.MigratedManifest
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.nio.file.Path

@Component
class CatalogClient(
    private val catalogRestClient: RestClient,
    private val resourceLoader: ResourceLoader,
    private val schemaMigrator: CatalogSchemaMigrator,
    @Value("\${epistola.catalog.allow-http:false}") private val allowHttp: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Bundled catalogs live on the classpath, which cannot change while this JVM runs, yet the same
    // manifest, resource details and binaries were read, schema-migrated and hashed again for every
    // tenant that installs them, every catalogs-page render and every boot. Cached like FontByteCache:
    // one Caffeine cache, weighed by the raw bytes each entry was loaded from, with an explicit
    // ceiling (every classpath catalog in the repo is under half a megabyte; the ceiling is the bound
    // stated out loud, not something that is expected to evict). No TTL: classpath content is
    // immutable. Only `classpath:` sources are cached; `file:` and HTTP sources can change underneath
    // a running process. Cached manifests and details are shared across tenants and threads: callers
    // read them and map them into their own structures; nothing writes into a fetched object.
    private val classpathCache = Caffeine.newBuilder()
        .maximumWeight(MAX_CACHED_CLASSPATH_BYTES)
        .weigher<String, CachedClasspathEntry> { _, entry -> entry.weight }
        .build<String, CachedClasspathEntry>()

    private class CachedClasspathEntry(val value: Any, val weight: Int)

    private inline fun <reified T : Any> cachedClasspath(key: String, crossinline load: () -> Pair<T, Int>): T = classpathCache.get(key) { load().let { (value, weight) -> CachedClasspathEntry(value, weight) } }.value as T

    private val allowedSchemes = buildSet {
        add("https")
        add("file")
        add("classpath")
        if (allowHttp) add("http")
    }

    /** The bound, current-shape manifest. Use [fetchMigratedManifest] when you also need to fetch details. */
    fun fetchManifest(url: String, authType: AuthType, credential: String?): CatalogManifest = fetchMigratedManifest(url, authType, credential).manifest

    /**
     * Like [fetchManifest], but also returns the [CatalogMigrationContext] that
     * must be threaded into [fetchResourceDetail] for every detail of the same
     * catalog (the catalog's source version + migrated manifest tree).
     */
    fun fetchMigratedManifest(url: String, authType: AuthType, credential: String?): MigratedManifest {
        validateUrl(url, allowedSchemes)
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
        val bytes = readLocalBinary(url) ?: fetchHttpBinary(url, authType, credential)
        return schemaMigrator.migrateAndBindManifest(bytes) to bytes.size
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
        validateUrl(resolvedUrl, allowedSchemes)
        if (resolvedUrl.startsWith("classpath:")) {
            // The migration context comes from the manifest, so the manifest URL is part of the key.
            return cachedClasspath("detail:$type\n$manifestUrl\n$resolvedUrl") {
                loadResourceDetail(type, resolvedUrl, authType, credential, catalog)
            }
        }
        return loadResourceDetail(type, resolvedUrl, authType, credential, catalog).first
    }

    private fun loadResourceDetail(
        type: String,
        resolvedUrl: String,
        authType: AuthType,
        credential: String?,
        catalog: CatalogMigrationContext,
    ): Pair<ResourceDetail, Int> {
        logger.debug("Fetching resource detail from {}", resolvedUrl)
        val bytes = readLocalBinary(resolvedUrl) ?: fetchHttpBinary(resolvedUrl, authType, credential)
        return schemaMigrator.migrateAndBindResourceDetail(type, bytes, catalog) to bytes.size
    }

    fun fetchBinaryContent(contentUrl: String, manifestUrl: String, authType: AuthType, credential: String?): ByteArray {
        val resolvedUrl = resolveContentUrl(contentUrl, manifestUrl)
        if (resolvedUrl.startsWith("classpath:")) {
            // Copied out so a caller that keeps the array cannot alter what the next tenant reads.
            return cachedClasspath<ByteArray>("binary:$resolvedUrl") {
                loadBinary(resolvedUrl, authType, credential).let { it to it.size }
            }.copyOf()
        }
        return loadBinary(resolvedUrl, authType, credential)
    }

    private fun loadBinary(resolvedUrl: String, authType: AuthType, credential: String?): ByteArray {
        logger.debug("Fetching binary content from {}", resolvedUrl)
        return readLocalBinary(resolvedUrl)
            ?: fetchHttpBinary(resolvedUrl, authType, credential)
    }

    private fun readLocalBinary(url: String): ByteArray? = when {
        url.startsWith("file:") -> {
            val path = Path.of(URI.create(url))
            if (!path.toFile().exists()) throw CatalogFetchException("File not found: $path")
            path.toFile().readBytes()
        }
        url.startsWith("classpath:") -> {
            val resource = resourceLoader.getResource(url)
            if (!resource.exists()) throw CatalogFetchException("Classpath resource not found: $url")
            resource.contentAsByteArray
        }
        else -> null
    }

    private fun fetchHttpBinary(url: String, authType: AuthType, credential: String?): ByteArray = catalogRestClient.get()
        .uri(url)
        .applyAuth(authType, credential)
        .retrieve()
        .body(ByteArray::class.java)
        ?: throw CatalogFetchException("Empty response from: $url")

    private fun RestClient.RequestHeadersSpec<*>.applyAuth(authType: AuthType, credential: String?): RestClient.RequestHeadersSpec<*> = apply {
        when (authType) {
            AuthType.NONE -> {}
            AuthType.BEARER -> header(HttpHeaders.AUTHORIZATION, "Bearer $credential")
            AuthType.API_KEY -> header("X-API-Key", credential ?: "")
        }
    }

    companion object {
        /** Ceiling of the classpath cache, in bytes of source content. Every bundled catalog together is well under 1 MiB. */
        private const val MAX_CACHED_CLASSPATH_BYTES: Long = 32L * 1024 * 1024

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

        fun resolveContentUrl(contentUrl: String, manifestUrl: String): String {
            if (contentUrl.startsWith("http://") ||
                contentUrl.startsWith("https://") ||
                contentUrl.startsWith("file:") ||
                contentUrl.startsWith("classpath:")
            ) {
                return contentUrl
            }
            if (manifestUrl.startsWith("classpath:")) {
                val basePath = manifestUrl.substringAfter("classpath:").substringBeforeLast("/")
                val resolved = "$basePath/$contentUrl".replace("/./", "/")
                return "classpath:$resolved"
            }
            val manifestUri = URI.create(manifestUrl)
            return manifestUri.resolve(contentUrl).toString()
        }

        fun resolveDetailUrl(detailUrl: String, manifestUrl: String): String {
            if (detailUrl.startsWith("http://") ||
                detailUrl.startsWith("https://") ||
                detailUrl.startsWith("file:") ||
                detailUrl.startsWith("classpath:")
            ) {
                return detailUrl
            }
            // For classpath: URLs, resolve relative paths by replacing the last segment
            if (manifestUrl.startsWith("classpath:")) {
                val basePath = manifestUrl.substringAfter("classpath:").substringBeforeLast("/")
                val resolved = "$basePath/$detailUrl".replace("/./", "/")
                return "classpath:$resolved"
            }
            val manifestUri = URI.create(manifestUrl)
            return manifestUri.resolve(detailUrl).toString()
        }
    }
}

class CatalogFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
