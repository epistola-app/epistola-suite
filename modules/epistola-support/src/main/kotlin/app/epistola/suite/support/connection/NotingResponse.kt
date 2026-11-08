// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.springframework.http.client.ClientHttpResponse
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A support service answer whose body tells [onFailure] when it breaks off while the caller reads it
 * (cut off, over the cap), so the connection notes it as unavailability; the failure is rethrown.
 */
internal class NotingResponse(private val delegate: ClientHttpResponse, private val onFailure: (IOException) -> Unit) : ClientHttpResponse by delegate {
    private val noted: InputStream by lazy {
        object : FilterInputStream(delegate.body) {
            override fun read(): Int = noted { super.read() }

            override fun read(b: ByteArray, off: Int, len: Int): Int = noted { super.read(b, off, len) }

            private fun <T> noted(read: () -> T): T = try {
                read()
            } catch (e: IOException) {
                onFailure(e)
                throw e
            }
        }
    }

    override fun getBody(): InputStream = noted
}
