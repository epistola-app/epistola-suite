// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.support.SupportProperties
import jakarta.annotation.PreDestroy
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.AbstractBufferingClientHttpRequest
import org.springframework.http.client.ClientHttpRequest
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one HTTP transport every call to the support service goes through, so a slow, hanging or
 * misbehaving service can never hold a suite thread for long (`docs/support-protocol.md`,
 * "Reliability"):
 *
 * - **A deadline for the whole call.** `epistola.support.read-timeout` bounds one call from sending
 *   the request to reading the last byte of the answer — not each read, as a socket timeout would:
 *   a service that trickles a byte every few seconds, or stops reading a large upload, is cut off
 *   all the same. A request with a body gets one second more per [UPLOAD_BYTES_PER_SECOND] bytes, so
 *   a large release archive is not cut off on a slow but working uplink.
 * - **A connect timeout** (`epistola.support.connect-timeout`).
 * - **A cap on the answer**: at most [MAX_RESPONSE_BYTES]. A larger answer, error pages included,
 *   fails the call with an [IOException] (so it reads as "unavailable") instead of being buffered.
 *
 * Every failure surfaces as an [IOException] inside the RestClient's exception, which
 * [isSupportUnavailable] reads as "try again later". The work is asynchronous underneath, so an
 * interrupted caller (a shutdown) stops waiting at once.
 */
@Component
class SupportHttp(private val properties: SupportProperties) {
    private val executor: ExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("support-http-", 0).factory())

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .executor(executor)
        .build()

    /** A request factory with the deadline and the cap; one for every client of the support service. */
    fun requestFactory(): ClientHttpRequestFactory = ClientHttpRequestFactory { uri, method -> DeadlineRequest(uri, method) }

    /** The deadline for one call that sends [bodyBytes] bytes. */
    fun deadline(bodyBytes: Int): Duration = properties.readTimeout.plusSeconds(bodyBytes.toLong() / UPLOAD_BYTES_PER_SECOND)

    @PreDestroy
    fun close() {
        httpClient.shutdownNow()
        executor.shutdownNow()
    }

    private inner class DeadlineRequest(private val uri: URI, private val method: HttpMethod) : AbstractBufferingClientHttpRequest() {
        override fun getMethod(): HttpMethod = method

        override fun getURI(): URI = uri

        override fun executeInternal(headers: HttpHeaders, bufferedOutput: ByteArray): ClientHttpResponse {
            val delegate: ClientHttpRequest = JdkClientHttpRequestFactory(httpClient, executor)
                .apply { setReadTimeout(deadline(bufferedOutput.size)) }
                .createRequest(uri, method)
            delegate.headers.putAll(headers)
            if (bufferedOutput.isNotEmpty()) delegate.body.write(bufferedOutput)
            val response = delegate.execute()
            val declared = response.headers.contentLength
            if (declared > MAX_RESPONSE_BYTES) {
                response.close()
                throw IOException("The support service answered with $declared bytes, more than the $MAX_RESPONSE_BYTES this suite reads")
            }
            return CappedResponse(response)
        }
    }

    private class CappedResponse(private val delegate: ClientHttpResponse) : ClientHttpResponse {
        private val capped: InputStream by lazy { CappedInputStream(delegate.body) }

        override fun getStatusCode(): HttpStatusCode = delegate.statusCode

        override fun getStatusText(): String = delegate.statusText

        override fun getHeaders(): HttpHeaders = delegate.headers

        override fun getBody(): InputStream = capped

        override fun close() = delegate.close()
    }

    private class CappedInputStream(input: InputStream) : FilterInputStream(input) {
        private var read = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            read += n
            if (read > MAX_RESPONSE_BYTES) {
                runCatching { close() }
                throw IOException("The support service answered with more than the $MAX_RESPONSE_BYTES bytes this suite reads")
            }
        }
    }

    companion object {
        /** The most of one answer this suite reads: protocol documents are kilobytes, a page of feedback updates well under this. */
        const val MAX_RESPONSE_BYTES: Long = 4L * 1024 * 1024

        /** The slowest upload a call still allows for: a request body adds one second per this many bytes to the deadline. */
        const val UPLOAD_BYTES_PER_SECOND: Long = 512L * 1024
    }
}
