package app.epistola.suite.apikeys

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Optional

/**
 * Short-TTL, bounded in-memory cache of API-key lookups, keyed by the SHA-256
 * key hash. It sits in front of `LookupApiKeyByHash` on the hot API
 * authentication path so a stream of requests with the same key does not hit
 * the database on every call.
 *
 * Security/correctness notes:
 * - **Negative results are cached** (an absent key → empty), bounding DB load
 *   from invalid-key floods.
 * - Entries are **short-lived** (default 60s) so disable/expiry changes converge
 *   even without explicit invalidation.
 * - Callers MUST still re-check [ApiKey.isUsable] on a hit: a cached-but-now-expired
 *   key fails the time check, and [invalidateAll] is called on revoke so a
 *   disabled key stops authenticating immediately.
 */
@Component
class ApiKeyAuthCache(
    @Value("\${epistola.security.api-key-cache.ttl-seconds:60}") ttlSeconds: Long = 60,
    @Value("\${epistola.security.api-key-cache.max-size:10000}") maxSize: Long = 10_000,
) {
    private val cache: Cache<String, Optional<ApiKey>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
        .maximumSize(maxSize)
        .build()

    /**
     * Returns the cached lookup for [keyHash], invoking [loader] on a miss and
     * caching its result (including a not-found null). The loader runs inside the
     * caller's mediator scope, so `*.query()` is valid within it.
     */
    fun get(keyHash: String, loader: (String) -> ApiKey?): ApiKey? = cache.get(keyHash) { hash ->
        Optional.ofNullable(loader(hash))
    }.orElse(null)

    /** Drops all cached entries. Called after a key is revoked/disabled. */
    fun invalidateAll() {
        cache.invalidateAll()
    }
}
