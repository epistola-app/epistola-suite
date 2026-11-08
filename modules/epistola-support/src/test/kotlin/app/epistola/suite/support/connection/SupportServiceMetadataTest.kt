// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI

class SupportServiceMetadataTest {
    private val mapper = JsonMapper.builder().build()
    private val issuer = URI("https://support.epistola.app")

    private fun document(
        issuer: String = "https://support.epistola.app",
        token: String = "https://support.epistola.app/oauth2/token",
        registration: String = "https://support.epistola.app/oauth2/register",
        api: String? = "https://support.epistola.app/api/v1",
        otlp: String? = "https://support.epistola.app/otlp",
        jwks: String? = "https://support.epistola.app/oauth2/jwks",
    ) = mapper.readTree(
        buildString {
            append("""{"issuer":"$issuer","token_endpoint":"$token","registration_endpoint":"$registration"""")
            api?.let { append(""","epistola_api_endpoint":"$it"""") }
            otlp?.let { append(""","epistola_otlp_endpoint":"$it"""") }
            jwks?.let { append(""","jwks_uri":"$it"""") }
            append("}")
        },
    )

    @Test
    fun `the protocol's example metadata is taken whole`() {
        val metadata = SupportServiceMetadata.verify(issuer, document(), allowHttp = false)

        assertThat(metadata.issuer).isEqualTo(issuer)
        assertThat(metadata.tokenEndpoint).isEqualTo(URI("https://support.epistola.app/oauth2/token"))
        assertThat(metadata.registrationEndpoint).isEqualTo(URI("https://support.epistola.app/oauth2/register"))
        assertThat(metadata.apiEndpoint).isEqualTo(URI("https://support.epistola.app/api/v1"))
        assertThat(metadata.otlpEndpoint).isEqualTo(URI("https://support.epistola.app/otlp"))
        assertThat(SupportServiceMetadata.location(issuer)).isEqualTo(URI("https://support.epistola.app/.well-known/oauth-authorization-server"))
    }

    @Test
    fun `an explicit default port and a trailing slash are the same origin`() {
        val metadata = SupportServiceMetadata.verify(
            URI("https://support.epistola.app/"),
            document(token = "https://SUPPORT.epistola.app:443/oauth2/token", api = "https://support.epistola.app/api/v1/"),
            allowHttp = false,
        )

        assertThat(metadata.apiEndpoint).isEqualTo(URI("https://support.epistola.app/api/v1"))
    }

    @Test
    fun `an issuer other than the one asked is refused`() {
        assertThatThrownBy { SupportServiceMetadata.verify(issuer, document(issuer = "https://support.evil.example"), allowHttp = false) }
            .isInstanceOf(SupportUnavailableException::class.java).hasMessageContaining("issuer mismatch")
    }

    @Test
    fun `an endpoint off the issuer's origin is refused, whichever it is`() {
        val off = listOf(
            document(token = "https://evil.example/oauth2/token"),
            document(registration = "https://support.epistola.app:8443/oauth2/register"),
            document(api = "https://api.epistola.app/api/v1"),
            document(otlp = "https://otlp.epistola.app/otlp"),
            document(jwks = "https://evil.example/jwks"),
        )
        off.forEach { doc ->
            assertThatThrownBy { SupportServiceMetadata.verify(issuer, doc, allowHttp = false) }
                .isInstanceOf(SupportUnavailableException::class.java).hasMessageContaining("not on the issuer's origin")
        }
    }

    @Test
    fun `a missing required endpoint is refused, a missing optional one is not`() {
        assertThatThrownBy { SupportServiceMetadata.verify(issuer, document(api = null), allowHttp = false) }
            .hasMessageContaining("epistola_api_endpoint")
        val metadata = SupportServiceMetadata.verify(issuer, document(otlp = null, jwks = null), allowHttp = false)
        assertThat(metadata.otlpEndpoint).isNull()
        assertThat(metadata.jwksUri).isNull()
    }

    @Test
    fun `plain http is refused unless allowed, and a scheme change is another origin`() {
        val local = URI("http://localhost:8095")
        val plain = document(
            issuer = "http://localhost:8095",
            token = "http://localhost:8095/oauth2/token",
            registration = "http://localhost:8095/oauth2/register",
            api = "http://localhost:8095/api/v1",
            otlp = "http://localhost:8095/otlp",
            jwks = null,
        )

        assertThatThrownBy { SupportServiceMetadata.verify(local, plain, allowHttp = false) }.hasMessageContaining("must use HTTPS")
        assertThat(SupportServiceMetadata.verify(local, plain, allowHttp = true).apiEndpoint).isEqualTo(URI("http://localhost:8095/api/v1"))
        assertThatThrownBy { SupportServiceMetadata.verify(issuer, document(token = "http://support.epistola.app/oauth2/token"), allowHttp = true) }
            .hasMessageContaining("not on the issuer's origin")
    }

    @Test
    fun `userinfo in an endpoint is refused`() {
        assertThatThrownBy { SupportServiceMetadata.verify(issuer, document(token = "https://support.epistola.app@evil.example/oauth2/token"), allowHttp = false) }
            .isInstanceOf(SupportUnavailableException::class.java)
    }

    @Test
    fun `scopes_supported says which services the support service offers, and may be absent`() {
        val node = document() as tools.jackson.databind.node.ObjectNode
        node.putArray("scopes_supported").add("telemetry").add("requests").add(" translation ").add(42)

        assertThat(SupportServiceMetadata.verify(issuer, node, allowHttp = false).scopesSupported).containsExactlyInAnyOrder("telemetry", "requests", "translation")
        assertThat(SupportServiceMetadata.verify(issuer, document(), allowHttp = false).scopesSupported).`as`("not stated").isNull()
    }
}
