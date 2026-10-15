// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.generation.pdf.FontFaceBytes
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FontByteCacheTest {

    @Test
    fun `caches a non-null result so the loader runs once`() {
        val cache = FontByteCache()
        val calls = AtomicInteger(0)
        val face = FontFaceBytes(byteArrayOf(1, 2, 3), contentHash = null)
        val loader = {
            calls.incrementAndGet()
            face
        }

        val first = cache.getOrResolve("k", loader)
        val second = cache.getOrResolve("k", loader)

        assertSame(face, first)
        assertSame(first, second)
        assertEquals(1, calls.get(), "loader must run only once for a cached key")
    }

    @Test
    fun `does not cache a null (not found) result`() {
        val cache = FontByteCache()
        val calls = AtomicInteger(0)
        val loader = {
            calls.incrementAndGet()
            null as FontFaceBytes?
        }

        assertNull(cache.getOrResolve("missing", loader))
        assertNull(cache.getOrResolve("missing", loader))
        assertEquals(2, calls.get(), "a missing font must not be negatively cached")
    }

    @Test
    fun `distinct keys are isolated`() {
        val cache = FontByteCache()
        val a = FontFaceBytes(byteArrayOf(10), contentHash = null)
        val b = FontFaceBytes(byteArrayOf(20), contentHash = null)

        assertSame(a, cache.getOrResolve("a") { a })
        assertSame(b, cache.getOrResolve("b") { b })
        assertSame(a, cache.getOrResolve("a") { error("should be cached") })
    }
}
