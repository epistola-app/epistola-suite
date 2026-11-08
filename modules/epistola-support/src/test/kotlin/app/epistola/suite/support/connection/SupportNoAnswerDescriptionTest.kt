// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.support.SupportProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.http.HttpConnectTimeoutException
import java.time.Duration

/**
 * [describeNoAnswer] on the failures the JDK HTTP client behind [SupportHttp] really raises: an
 * administrator must be told a mistyped host, or a network that drops packets, apart from a stopped
 * service.
 */
class SupportNoAnswerDescriptionTest {
    private val http = SupportHttp(SupportProperties(connectTimeout = Duration.ofSeconds(2), readTimeout = Duration.ofSeconds(2)))

    @AfterEach
    fun close() = http.close()

    private fun failure(url: String): RestClientException {
        val client = RestClient.builder().requestFactory(http.requestFactory()).build()
        return runCatching { client.get().uri(url).retrieve().toBodilessEntity() }.exceptionOrNull() as RestClientException
    }

    @Test
    fun `a host that does not resolve is named as an unknown host`() {
        // RFC 2606: .invalid never resolves.
        assertThat(describeNoAnswer(failure("http://no-such-host.invalid:8095/.well-known/x")))
            .isEqualTo("the support service could not be reached (unknown host)")
    }

    @Test
    fun `a port nobody listens on is named as connection refused`() {
        val port = ServerSocket(0).use { it.localPort }

        assertThat(describeNoAnswer(failure("http://127.0.0.1:$port/.well-known/x")))
            .isEqualTo("the support service could not be reached (connection refused)")
    }

    /**
     * The chain the JDK client raises when a firewall drops the packets (JDK 25: an
     * [HttpConnectTimeoutException] caused by a [ConnectException] with the same message), built
     * here, as a dropping network cannot be relied on in a test.
     */
    @Test
    fun `a connection that times out is named as such, not as refused`() {
        val timedOut = HttpConnectTimeoutException("HTTP connect timed out").apply { initCause(ConnectException("HTTP connect timed out")) }

        assertThat(describeNoAnswer(ResourceAccessException("I/O error on GET request for \"http://10.255.255.1:81/\": HTTP connect timed out", timedOut)))
            .isEqualTo("the support service could not be reached (the connection timed out)")
    }

    /**
     * The chain the JDK client raises when the host is unreachable (a firewall that rejects with
     * ICMP host-unreachable or prohibited, no route): `Utils.toConnectException` wraps the
     * [NoRouteToHostException], or the [SocketException] "Network is unreachable", in a
     * [ConnectException] with the cause's message (JDK 25). Built here, as no test network
     * can be relied on to reject that way.
     */
    @ParameterizedTest
    @ValueSource(strings = ["no-route", "network-unreachable"])
    fun `an unreachable host or network is named as such, not as refused`(kind: String) {
        val cause: SocketException = if (kind == "no-route") NoRouteToHostException("No route to host") else SocketException("Network is unreachable")
        val wrapped = ConnectException(cause.message).apply { initCause(cause) }

        val described = describeNoAnswer(ResourceAccessException("I/O error on GET request for \"https://support.example/\": $cause", wrapped))

        assertThat(described).doesNotContain("connection refused").contains(cause.message!!).contains("network")
    }
}
