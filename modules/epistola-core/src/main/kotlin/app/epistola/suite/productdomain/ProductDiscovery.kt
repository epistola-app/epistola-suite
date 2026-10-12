// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The last discovery attempt, shared by every node through `app_metadata`. */
data class ProductDiscoveryStatus(
    /** The product domain the document came from; a cached copy for another domain is not used. */
    val domain: String? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val discovered: DiscoveredEndpoints? = null,
    val lastError: String? = null,
)

/**
 * Finds Epistola-operated services through `https://{product-domain}/.well-known/epistola.json`
 * (ADR 0030).
 *
 * The document is fetched only when an integration asks for an endpoint, so an installation that
 * uses none of them, or names every endpoint itself, never fetches it. A fetched copy is shared by
 * every node and refreshed at most daily; a failed attempt keeps the last good copy and is retried
 * after an hour. Without any copy each integration falls back to [ProductEndpoints.defaults], so
 * discovery never makes a check fail that would have worked without it.
 */
@Service
class ProductDiscovery(
    private val domain: ProductDomainProperties,
    private val metadata: AppMetadataService,
    private val client: ProductDiscoveryClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()

    /** The endpoints to call now; fetches the document first when the cached copy is due. */
    fun endpoints(): ProductEndpoints = resolve(lock.withLock { if (isDue(readStatus())) refresh() else readStatus() })

    /** The endpoints from the cached copy, without any network call: for pages that show them. */
    fun cachedEndpoints(): ProductEndpoints = resolve(readStatus())

    fun status(): ProductDiscoveryStatus? = readStatus()?.takeIf { it.domain == domain.productDomain }

    /** Fetches the document now. Never throws: a failure is recorded and the last good copy kept. */
    fun refresh(): ProductDiscoveryStatus {
        val previous = status()
        val now = EpistolaClock.instant()
        val status = try {
            val discovered = DiscoveredEndpoints.parse(client.fetch(domain.discoveryUrl), domain)
            if (discovered.rejected.isNotEmpty()) {
                log.warn("Product discovery at {} named endpoints the Suite refuses: {}", domain.discoveryUrl, discovered.rejected)
            }
            ProductDiscoveryStatus(domain.productDomain, now, now, discovered)
        } catch (e: Exception) {
            log.info("Product discovery at {} failed, using {}: {}", domain.discoveryUrl, if (previous?.discovered != null) "the last good copy" else "the built-in defaults", e.message)
            (previous ?: ProductDiscoveryStatus(domain.productDomain)).copy(lastAttemptAt = now, lastError = e.message ?: e.javaClass.simpleName)
        }
        metadata.setAs(STATUS_KEY, status)
        return status
    }

    private fun resolve(status: ProductDiscoveryStatus?): ProductEndpoints {
        val defaults = ProductEndpoints.defaults(domain)
        val discovered = status?.takeIf { it.domain == domain.productDomain }?.discovered ?: return defaults
        return discovered.over(defaults)
    }

    private fun isDue(status: ProductDiscoveryStatus?): Boolean {
        if (status == null || status.domain != domain.productDomain) return true
        val last = status.lastAttemptAt ?: return true
        val wait = if (status.lastError == null) REFRESH_AFTER else RETRY_AFTER
        return Duration.between(last, EpistolaClock.instant()) >= wait
    }

    /** A stored status from another build must not break discovery: it degrades to "never fetched". */
    private fun readStatus(): ProductDiscoveryStatus? = try {
        metadata.getAs<ProductDiscoveryStatus>(STATUS_KEY)
    } catch (e: Exception) {
        log.debug("Ignoring unreadable cached product discovery status: {}", e.message)
        null
    }

    companion object {
        const val STATUS_KEY = "product-discovery.status"
        val REFRESH_AFTER: Duration = Duration.ofDays(1)
        val RETRY_AFTER: Duration = Duration.ofHours(1)
    }
}

/**
 * Fetches the discovery document. Anonymous: it sends no installation id and no version. The
 * response is untrusted, read up to [MAX_BYTES] and validated field by field by
 * [DiscoveredEndpoints.parse].
 */
open class ProductDiscoveryClient(connectTimeout: Duration, readTimeout: Duration) {
    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(connectTimeout)
                setReadTimeout(readTimeout)
            },
        )
        .build()
    private val mapper = JsonMapper.builder().build()

    open fun fetch(url: String): JsonNode = try {
        restClient.get()
            .uri(url)
            .headers { http -> HEADERS.forEach { (name, value) -> http.set(name, value) } }
            .exchange { _, response ->
                val status = response.statusCode.value()
                if (status != 200) throw ProductDiscoveryException("Discovery document returned HTTP $status")
                val bytes = response.body.use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) throw ProductDiscoveryException("Discovery document larger than $MAX_BYTES bytes")
                mapper.readTree(bytes)
            }
    } catch (e: RestClientException) {
        throw ProductDiscoveryException("Discovery document could not be fetched: ${e.message}", e)
    }

    companion object {
        const val MAX_BYTES = 16 * 1024

        /** Everything the request carries, for the "Shared with Epistola" page. */
        val HEADERS: Map<String, String> = mapOf("Accept" to "application/json", "User-Agent" to "epistola-suite")
    }
}
