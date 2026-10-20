// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The bytes of font faces, by content hash, so a render does not fetch a face's binary from the
 * content store for every face it embeds.
 *
 * Keyed by `tenant|content hash`: which face a render uses is decided in the version it renders
 * ([app.epistola.suite.fonts.queries.ResolveFontFace]), and the bytes behind a hash never change, so
 * an entry can never stand in for another version's face. Bounded by total cached bytes (a face is
 * ~100–700 KB) and evicted a while after write.
 *
 * Negative results are not cached: a missing face should resolve as soon as it is imported.
 */
@Component
class FontByteCache {

    private val cache = Caffeine.newBuilder()
        .maximumWeight(MAX_TOTAL_BYTES)
        .weigher<String, ByteArray> { _, value -> value.size }
        .expireAfterWrite(Duration.ofMinutes(10))
        .build<String, ByteArray>()

    /** The cached bytes for [key], or [loader]'s; a non-null result is cached, a null is not. */
    fun getOrLoad(key: String, loader: () -> ByteArray?): ByteArray? {
        cache.getIfPresent(key)?.let { return it }
        val loaded = loader() ?: return null
        cache.put(key, loaded)
        return loaded
    }

    private companion object {
        const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
    }
}
