// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.banner

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Optional

/**
 * Reads/writes the single installation-wide [SiteBanner] stored under the
 * `site_banner` key in `app_metadata`, fronted by a short-TTL in-memory cache.
 *
 * The banner is resolved on the shell render path — i.e. on **every page** — so a
 * DB read per request would be wasteful for a value that changes rarely. Caffeine
 * (same idiom as `ApiKeyAuthCache`) caches the singleton for [ttlSeconds]; each
 * node caches independently, so a change made on one node converges on the others
 * within the TTL, while a write on this node invalidates immediately.
 */
@Component
class SiteBannerStore(
    private val metadata: AppMetadataService,
    @Value("\${epistola.site-banner.cache.ttl-seconds:60}") ttlSeconds: Long = 60,
) {
    private val cache: Cache<String, Optional<SiteBanner>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
        .maximumSize(1)
        .build()

    /** The stored banner (enabled or not), or null if none has ever been set. */
    fun get(): SiteBanner? = cache.get(KEY) {
        Optional.ofNullable(metadata.getAs<SiteBanner>(KEY))
    }.orElse(null)

    /** Upserts the banner and invalidates the cache so the change is visible at once. */
    fun set(banner: SiteBanner) {
        metadata.setAs(KEY, banner)
        cache.invalidateAll()
    }

    /**
     * Writes [banner] only if no banner is stored yet. Returns true if it wrote.
     * Used by the demo bootstrap so it seeds once and never overwrites an admin's edit.
     */
    fun seedIfAbsent(banner: SiteBanner): Boolean {
        val inserted = metadata.setIfAbsent(KEY, banner)
        if (inserted) cache.invalidateAll()
        return inserted
    }

    /** Drops the cache. Exposed for tests that mutate `app_metadata` directly. */
    internal fun invalidate() {
        cache.invalidateAll()
    }

    private companion object {
        const val KEY = "site_banner"
    }
}
