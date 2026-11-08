// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.net.ConnectException

/** Pins the scope names to `docs/support-protocol.md`, which the support service also implements. */
class SupportScopeTest {
    @Test
    fun `the scopes are the protocol's wire values`() {
        assertThat(SupportScope.entries.map { it.value }).containsExactly("telemetry", "requests", "mirror", "usage")
    }

    @Test
    fun `a scope string parses, dropping values this suite does not know`() {
        assertThat(SupportScope.parse("requests")).isEqualTo(SupportScope.REQUESTS)
        assertThat(SupportScope.parse("FEEDBACK")).isNull()
        assertThat(SupportScope.parseAll("telemetry requests backups")).containsExactlyInAnyOrder(SupportScope.TELEMETRY, SupportScope.REQUESTS)
        assertThat(SupportScope.parseAll("")).isEmpty()
        assertThat(SupportScope.parseAll(null)).isEmpty()
    }

    @Test
    fun `unavailability is found anywhere in the causes`() {
        assertThat(SupportUnavailableException("down").isSupportUnavailable()).isTrue()
        assertThat(IllegalStateException("wrapped", ConnectException("refused")).isSupportUnavailable()).isTrue()
        assertThat(ResourceAccessException("timeout", IOException("read timed out")).isSupportUnavailable()).isTrue()
        assertThat(HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE).isSupportUnavailable()).isTrue()
        assertThat(HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS).isSupportUnavailable()).isTrue()
    }

    @Test
    fun `a refusal or a client error is not unavailability`() {
        assertThat(SupportRefusedException("invalid_client").isSupportUnavailable()).isFalse()
        assertThat(HttpClientErrorException(HttpStatus.BAD_REQUEST).isSupportUnavailable()).isFalse()
        assertThat(HttpClientErrorException(HttpStatus.FORBIDDEN).isSupportUnavailable()).isFalse()
        assertThat(IllegalArgumentException("bad input").isSupportUnavailable()).isFalse()
    }

    @Test
    fun `a quota the service enforces is not unavailability, though it is a 5xx`() {
        // 507 quota_exceeded: waiting does not help, so the item counts an attempt instead of being deferred forever.
        assertThat(HttpServerErrorException(HttpStatus.INSUFFICIENT_STORAGE).isSupportUnavailable()).isFalse()
        assertThat(IllegalStateException("wrapped", HttpServerErrorException(HttpStatus.INSUFFICIENT_STORAGE)).isSupportUnavailable()).isFalse()
    }

    @Test
    fun `a 403 insufficient_scope is recognised by its challenge or its body, a plain 403 is not`() {
        val challenge = HttpHeaders().apply { set("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"mirror\"") }
        val byChallenge = HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", challenge, ByteArray(0), null)
        val byBody = HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders(), """{"error":"insufficient_scope"}""".toByteArray(), null)

        assertThat(byChallenge.isInsufficientScope()).isTrue()
        assertThat(IllegalStateException("wrapped", byBody).isInsufficientScope()).isTrue()
        assertThat(HttpClientErrorException(HttpStatus.FORBIDDEN).isInsufficientScope()).isFalse()
        assertThat(HttpClientErrorException(HttpStatus.UNAUTHORIZED).isInsufficientScope()).isFalse()
        assertThat(byChallenge.isSupportUnavailable()).`as`("not a reason to wait and retry").isFalse()
    }
}
