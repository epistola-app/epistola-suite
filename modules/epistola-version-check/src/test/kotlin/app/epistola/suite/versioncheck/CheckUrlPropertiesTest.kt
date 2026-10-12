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

/** A mistyped check URL fails startup instead of failing quietly every day after it. */
class CheckUrlPropertiesTest {
    @Test
    fun `the defaults are valid`() {
        assertThat(VersionCheckProperties().wellKnownUrl).startsWith("https://")
        assertThat(InstallationCheckProperties().url).contains("{id}")
    }

    @Test
    fun `an installation check url without the id placeholder is refused`() {
        // Every installation would ask for the same URL, and the badge would show someone else's status.
        assertThatThrownBy { InstallationCheckProperties(url = "https://mirror.example/installation.json") }
            .hasMessageContaining("epistola.installation-check.url")
            .hasMessageContaining("{id}")
    }

    @Test
    fun `check urls must be absolute http or https urls`() {
        for (url in listOf("", "epistola.app/installation/{id}.json", "file:///etc/{id}", "ftp://epistola.app/{id}")) {
            assertThatThrownBy { InstallationCheckProperties(url = url) }.describedAs(url)
                .hasMessageContaining("epistola.installation-check.url")
        }
        for (url in listOf("", "epistola.app/releases.json", "file:///etc/releases.json")) {
            assertThatThrownBy { VersionCheckProperties(wellKnownUrl = url) }.describedAs(url)
                .hasMessageContaining("epistola.version-check.well-known-url")
        }
    }

    @Test
    fun `plain http stays accepted, as it was before`() {
        assertThat(InstallationCheckProperties(url = "http://127.0.0.1:9/installation/{id}.json").url).startsWith("http:")
        assertThat(VersionCheckProperties(wellKnownUrl = "http://127.0.0.1:9/releases.json").wellKnownUrl).startsWith("http:")
    }

    @Test
    fun `a disabled version check does not validate its url`() {
        assertThat(VersionCheckProperties(enabled = false, wellKnownUrl = "").enabled).isFalse()
    }

    @Test
    fun `binding a bad url fails, so the application does not start`() {
        val binder = Binder(MapConfigurationPropertySource(mapOf("epistola.installation-check.url" to "https://mirror.example/installation.json")))

        assertThatThrownBy { binder.bindOrCreate("epistola.installation-check", InstallationCheckProperties::class.java) }
            .isInstanceOf(BindException::class.java)
            .rootCause().hasMessageContaining("{id}")
    }
}
