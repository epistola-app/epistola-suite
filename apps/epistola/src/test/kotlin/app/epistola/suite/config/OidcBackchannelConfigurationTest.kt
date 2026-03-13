package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OidcBackchannelConfigurationTest {

    @Test
    fun `extractPath returns path from URL with port`() {
        val path = OidcBackchannelConfiguration.extractPath("http://localhost:8081/realms/valtimo")
        assertThat(path).isEqualTo("/realms/valtimo")
    }

    @Test
    fun `extractPath returns path from URL without port`() {
        val path = OidcBackchannelConfiguration.extractPath("https://auth.example.com/realms/prod")
        assertThat(path).isEqualTo("/realms/prod")
    }

    @Test
    fun `extractPath returns empty string for URL without path`() {
        val path = OidcBackchannelConfiguration.extractPath("http://localhost:8080")
        assertThat(path).isEqualTo("")
    }

    @Test
    fun `rewriteUrl replaces scheme host and port but keeps path`() {
        val result = OidcBackchannelConfiguration.rewriteUrl(
            "http://localhost:8081/realms/valtimo/protocol/openid-connect/token",
            "http://keycloak:8080",
        )
        assertThat(result).isEqualTo("http://keycloak:8080/realms/valtimo/protocol/openid-connect/token")
    }

    @Test
    fun `rewriteUrl handles different backchannel base URLs`() {
        val result = OidcBackchannelConfiguration.rewriteUrl(
            "https://auth.example.com/realms/prod/protocol/openid-connect/certs",
            "http://internal-idp:9090",
        )
        assertThat(result).isEqualTo("http://internal-idp:9090/realms/prod/protocol/openid-connect/certs")
    }

    @Test
    fun `rewriteUrl returns null for null input`() {
        val result = OidcBackchannelConfiguration.rewriteUrl(null, "http://keycloak:8080")
        assertThat(result).isNull()
    }

    @Test
    fun `rewriteUrl handles URL without path`() {
        val result = OidcBackchannelConfiguration.rewriteUrl(
            "http://localhost:8081",
            "http://keycloak:8080",
        )
        assertThat(result).isEqualTo("http://keycloak:8080")
    }
}
