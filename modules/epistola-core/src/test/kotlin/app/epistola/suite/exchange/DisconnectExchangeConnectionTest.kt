// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class DisconnectExchangeConnectionTest : IntegrationTestBase() {
    @Test
    fun `disconnect removes the connection and pending authorization created by production commands`() {
        testClock.set(Instant.now())
        val tenant = createTenant("exchange-disconnect")

        withMediator {
            StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()

            assertThat(GetExchangeConnection(tenant.id).query()).isNotNull
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(latestState.get())).query()).isEqualTo(tenant.id)

            DisconnectExchangeConnection(tenant.id).execute()

            assertThat(GetExchangeConnection(tenant.id).query()).isNull()
            assertThat(FindExchangeAuthorizationTenant(requireNotNull(latestState.get())).query()).isNull()
        }
    }

    companion object {
        private val latestState = AtomicReference<String>()
        private val exchangeServer: HttpServer by lazy {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/.well-known/oauth-authorization-server") { exchange ->
                    val issuer = "http://127.0.0.1:${address.port}"
                    exchange.respond(
                        """
                        {
                          "issuer": "$issuer",
                          "authorization_request_endpoint": "$issuer/oauth/authorization-requests",
                          "token_endpoint": "$issuer/oauth/token"
                        }
                        """.trimIndent(),
                    )
                }
                createContext("/oauth/authorization-requests") { exchange ->
                    val parameters = exchange.requestBody.bufferedReader().use { it.readText() }
                        .split('&')
                        .associate { field ->
                            val (name, value) = field.split('=', limit = 2)
                            URLDecoder.decode(name, StandardCharsets.UTF_8) to
                                URLDecoder.decode(value, StandardCharsets.UTF_8)
                        }
                    latestState.set(parameters.getValue("state"))
                    exchange.respond(
                        """{"authorization_uri":"http://exchange.example/authorize","expires_in":300}""",
                    )
                }
                start()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { "http://127.0.0.1:${exchangeServer.address.port}" }
        }

        private fun HttpExchange.respond(body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
