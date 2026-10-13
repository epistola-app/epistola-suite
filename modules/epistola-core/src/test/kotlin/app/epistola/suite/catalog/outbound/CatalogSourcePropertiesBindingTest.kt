// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The Helm chart renders the list settings as single comma-separated environment variables
 * (`charts/epistola/templates/deployment.yaml`). This pins that Spring binds them as lists.
 */
class CatalogSourcePropertiesBindingTest {

    private fun bind(env: Map<String, Any>): CatalogSourceProperties {
        val environment = StandardEnvironment()
        // Spring maps EPISTOLA_CATALOG_FILEROOTS-style names only for a source named like the real
        // system environment, so the test source carries that suffix.
        environment.propertySources.addFirst(SystemEnvironmentPropertySource("chart-systemEnvironment", env))
        return Binder(ConfigurationPropertySources.get(environment))
            .bindOrCreate("epistola.catalog", CatalogSourceProperties::class.java)
    }

    @Test
    fun `comma-separated environment variables from the chart bind as lists`() {
        val properties = bind(
            mapOf(
                "EPISTOLA_CATALOG_FILEROOTS" to "/srv/catalogs,/mnt/shared",
                "EPISTOLA_CATALOG_OUTBOUND_ALLOWPRIVATENETWORKS" to "true",
                "EPISTOLA_CATALOG_OUTBOUND_ALLOWEDHOSTS" to "catalogs.intranet.example,codes.intranet.example",
            ),
        )

        assertThat(properties.fileRoots).containsExactly("/srv/catalogs", "/mnt/shared")
        assertThat(properties.outbound.allowPrivateNetworks).isTrue()
        assertThat(properties.outbound.allowedHosts)
            .containsExactly("catalogs.intranet.example", "codes.intranet.example")
    }

    @Test
    fun `without chart settings the rules stay closed`() {
        val properties = bind(emptyMap())

        assertThat(properties.fileRoots).isEmpty()
        assertThat(properties.outbound.allowPrivateNetworks).isFalse()
        assertThat(properties.outbound.allowedHosts).isEmpty()
    }
}
