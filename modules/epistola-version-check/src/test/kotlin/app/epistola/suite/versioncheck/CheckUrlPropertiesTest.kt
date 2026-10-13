// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/** A mistyped check endpoint fails startup instead of failing quietly every day after it. */
class CheckUrlPropertiesTest {
    @Test
    fun `by default both endpoints are blank, so they are found through the product domain`() {
        assertThat(VersionCheckProperties().endpoint).isBlank()
        assertThat(InstallationCheckProperties().endpoint).isBlank()
    }

    @Test
    fun `an installation check endpoint without the id placeholder is refused`() {
        // Every installation would ask for the same URL, and the badge would show someone else's status.
        assertThatThrownBy { InstallationCheckProperties(endpoint = "https://mirror.example/installation.json") }
            .hasMessageContaining("epistola.installation-check.endpoint")
            .hasMessageContaining("{id}")
    }

    @Test
    fun `check endpoints must be absolute http or https urls`() {
        for (url in listOf("epistola.app/installation/{id}.json", "file:///etc/{id}", "ftp://epistola.app/{id}")) {
            assertThatThrownBy { InstallationCheckProperties(endpoint = url) }.describedAs(url)
                .hasMessageContaining("epistola.installation-check.endpoint")
        }
        for (url in listOf("epistola.app/releases.json", "file:///etc/releases.json")) {
            assertThatThrownBy { VersionCheckProperties(endpoint = url) }.describedAs(url)
                .hasMessageContaining("epistola.version-check.endpoint")
        }
    }

    @Test
    fun `an operator may point either check at plain http`() {
        assertThat(InstallationCheckProperties(endpoint = "http://127.0.0.1:9/installation/{id}.json").endpoint).startsWith("http:")
        assertThat(VersionCheckProperties(endpoint = "http://127.0.0.1:9/releases.json").endpoint).startsWith("http:")
    }

    @Test
    fun `a disabled version check does not validate its endpoint`() {
        assertThat(VersionCheckProperties(enabled = false, endpoint = "not a url").enabled).isFalse()
    }

    @Test
    fun `binding a bad endpoint fails, so the application does not start`() {
        val binder = Binder(MapConfigurationPropertySource(mapOf("epistola.installation-check.endpoint" to "https://mirror.example/installation.json")))

        assertThatThrownBy { binder.bindOrCreate("epistola.installation-check", InstallationCheckProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .rootCause().hasMessageContaining("{id}")
    }
}
