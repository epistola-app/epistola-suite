// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Process-wide cache of resolved font-face bytes, shared across renders so a
 * document (and repeated renders of the same template) don't re-hit the DB +
 * content store for every text run.
 *
 * Bounded by total cached bytes (a font face is ~100–700 KB) and evicted a
 * while after write — short enough that a draft re-upload is picked up
 * reasonably soon. Published-version determinism does **not** rely on this
 * cache: [FontSnapshotVerifier] reads the live `content_hash` directly and
 * fails the render before bytes are fetched, so a stale entry can never mask
 * font drift on a published version.
 *
 * Negative results are not cached: a missing family should resolve as soon as
 * its faces are imported.
 */
@Component
class FontByteCache {

    private val cache = Caffeine.newBuilder()
        .maximumWeight(MAX_TOTAL_BYTES)
        .weigher<String, ByteArray> { _, value -> value.size }
        .expireAfterWrite(Duration.ofMinutes(10))
        .build<String, ByteArray>()

    /**
     * Returns the cached bytes for [key], or invokes [loader]; a non-null
     * result is cached, a null (not found) is not.
     */
    fun getOrResolve(key: String, loader: () -> ByteArray?): ByteArray? {
        cache.getIfPresent(key)?.let { return it }
        val resolved = loader() ?: return null
        cache.put(key, resolved)
        return resolved
    }

    private companion object {
        const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
    }
}
