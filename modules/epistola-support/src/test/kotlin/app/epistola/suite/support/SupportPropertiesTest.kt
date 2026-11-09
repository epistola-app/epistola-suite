// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI

/** The support connection's settings are refused at startup when they cannot work. */
class SupportPropertiesTest {
    @Test
    fun `a usage backlog under an hour stops startup`() {
        assertThatThrownBy { SupportProperties(usage = SupportProperties.Usage(backlog = java.time.Duration.ofMinutes(30))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("epistola.support.usage.backlog")
        assertThat(SupportProperties().usage.backlog).isEqualTo(java.time.Duration.ofHours(72))
    }

    @Test
    fun `an https endpoint is taken as the issuer`() {
        assertThat(SupportProperties(endpoint = "https://support.example/").configuredIssuer).isEqualTo(URI("https://support.example"))
    }

    @Test
    fun `a plaintext endpoint without allow-http stops startup instead of failing every check later`() {
        assertThatThrownBy { SupportProperties(enabled = true, endpoint = "http://support.internal") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("epistola.support.allow-http")
    }

    @Test
    fun `a plaintext endpoint is accepted with allow-http, for a local support service`() {
        assertThat(SupportProperties(enabled = true, endpoint = "http://localhost:8095", allowHttp = true).configuredIssuer).isEqualTo(URI("http://localhost:8095"))
    }

    @Test
    fun `an endpoint that is not a URL stops startup`() {
        assertThatThrownBy { SupportProperties(endpoint = "support.example:443") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `services are normalised, and a name this version does not know is kept and reported, never refused`() {
        val properties = SupportProperties(services = listOf(" Requests", "mirror", "", "requests", "translation"))

        assertThat(properties.configuredServices).containsExactly("mirror", "requests", "translation")
        assertThat(properties.unknownServices).containsExactly("translation")
        assertThat(SupportProperties().configuredServices).`as`("nothing selected by default").isEmpty()
    }
}
