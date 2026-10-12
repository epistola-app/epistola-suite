// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

class DiscoveredEndpointsTest {
    private val domain = ProductDomainProperties()
    private val mapper = JsonMapper.builder().build()

    private fun parse(json: String) = DiscoveredEndpoints.parse(mapper.readTree(json), domain)

    @Test
    fun `a complete document names every endpoint`() {
        val endpoints = parse(
            """
            {
              "schemaVersion": 1,
              "versionCheck": { "endpoint": "https://epistola.app/releases.json" },
              "installationCheck": { "endpoint": "https://epistola.app/installation/{id}.json" },
              "exchange": { "issuer": "https://exchange.epistola.app", "endpoint": "https://exchange.epistola.app/" },
              "supportHub": { "endpoint": "hub.epistola.app:443" },
              "somethingNew": { "endpoint": "https://epistola.app/new" }
            }
            """,
        ).over(ProductEndpoints.defaults(domain))

        assertThat(endpoints.versionCheck).isEqualTo("https://epistola.app/releases.json")
        assertThat(endpoints.installationCheck).isEqualTo("https://epistola.app/installation/{id}.json")
        assertThat(endpoints.exchange).isEqualTo(ProductEndpoints.ExchangeEndpoint("https://exchange.epistola.app", "https://exchange.epistola.app"))
        assertThat(endpoints.supportHub).isEqualTo(ProductEndpoints.HubAddress("hub.epistola.app", 443))
    }

    /** Pins the contract against what the website actually builds (website `src/lib/server/discovery/document.ts`). */
    @Test
    fun `the document epistola_app publishes is taken whole, and the hub keeps its default`() {
        val published = """{"schemaVersion":1,"refreshAfter":"PT1H","versionCheck":{"endpoint":"https://epistola.app/releases.json"},""" +
            """"installationCheck":{"endpoint":"https://epistola.app/installation/{id}.json"},""" +
            """"exchange":{"issuer":"https://exchange.epistola.app","endpoint":"https://exchange.epistola.app"}}"""

        val discovered = parse(published)
        val endpoints = discovered.over(ProductEndpoints.defaults(domain))

        assertThat(discovered.rejected).isEmpty()
        assertThat(discovered.refreshAfter).isEqualTo(Duration.ofHours(1))
        assertThat(endpoints.versionCheck).isEqualTo("https://epistola.app/releases.json")
        assertThat(endpoints.installationCheck).isEqualTo("https://epistola.app/installation/{id}.json")
        assertThat(endpoints.exchange.endpoint).isEqualTo("https://exchange.epistola.app")
        assertThat(endpoints.supportHub).isEqualTo(ProductEndpoints.defaults(domain).supportHub)
    }

    @Test
    fun `an integration the document leaves out keeps its built-in default`() {
        val defaults = ProductEndpoints.defaults(domain)

        val endpoints = parse("""{ "schemaVersion": 1, "versionCheck": { "endpoint": "https://epistola.app/releases.json" } }""").over(defaults)

        assertThat(endpoints.versionCheck).isEqualTo("https://epistola.app/releases.json")
        assertThat(endpoints.installationCheck).isEqualTo(defaults.installationCheck)
        assertThat(endpoints.exchange).isEqualTo(defaults.exchange)
        assertThat(endpoints.supportHub).isEqualTo(defaults.supportHub)
    }

    @Test
    fun `endpoints off the product domain, over plain http or without the id are refused one by one`() {
        val discovered = parse(
            """
            {
              "schemaVersion": 1,
              "versionCheck": { "endpoint": "https://evil.example/releases.json" },
              "installationCheck": { "endpoint": "https://epistola.app/installation.json" },
              "exchange": { "issuer": "http://exchange.epistola.app", "endpoint": "https://exchange.epistola.app" },
              "supportHub": { "endpoint": "hub.notepistola.app:443" }
            }
            """,
        )

        assertThat(discovered.versionCheck).isNull()
        assertThat(discovered.installationCheck).isNull()
        assertThat(discovered.exchangeIssuer).isNull()
        assertThat(discovered.supportHub).isNull()
        assertThat(discovered.rejected).hasSize(4)
        // A half-refused Exchange pair is not used: issuer and endpoint come together or not at all.
        assertThat(discovered.over(ProductEndpoints.defaults(domain)).exchange).isEqualTo(ProductEndpoints.defaults(domain).exchange)
    }

    @Test
    fun `a look-alike host or userinfo does not pass as the product domain`() {
        val discovered = parse(
            """
            {
              "schemaVersion": 1,
              "versionCheck": { "endpoint": "https://epistola.app.evil.example/releases.json" },
              "installationCheck": { "endpoint": "https://epistola.app@evil.example/installation/{id}.json" }
            }
            """,
        )

        assertThat(discovered.versionCheck).isNull()
        assertThat(discovered.installationCheck).isNull()
    }

    @Test
    fun `an unknown schema version is refused as a whole`() {
        assertThatThrownBy { parse("""{ "schemaVersion": 2, "versionCheck": { "endpoint": "https://epistola.app/r.json" } }""") }
            .isInstanceOf(ProductDiscoveryException::class.java)
        assertThatThrownBy { parse("""{ "versionCheck": { "endpoint": "https://epistola.app/r.json" } }""") }
            .isInstanceOf(ProductDiscoveryException::class.java)
    }

    @Test
    fun `the defaults are the addresses the Suite used before discovery, on the product domain`() {
        val defaults = ProductEndpoints.defaults(ProductDomainProperties("staging.epistola.app"))

        assertThat(defaults.versionCheck).isEqualTo("https://staging.epistola.app/.well-known/epistola/releases.json")
        assertThat(defaults.installationCheck).isEqualTo("https://staging.epistola.app/installation/{id}.json")
        assertThat(defaults.exchange.endpoint).isEqualTo("https://exchange.staging.epistola.app")
        assertThat(defaults.supportHub.toString()).isEqualTo("hub.staging.epistola.app:443")
    }

    @Test
    fun `refreshAfter sets how long a copy stays valid, within 15 minutes and a day`() {
        fun refresh(value: String) = parse("""{ "schemaVersion": 1, "refreshAfter": $value }""").refreshAfter

        assertThat(parse("""{ "schemaVersion": 1 }""").refreshAfter).isNull()
        assertThat(refresh("\"PT24H\"")).isEqualTo(Duration.ofHours(24))
        assertThat(refresh("\"PT15M\"")).isEqualTo(Duration.ofMinutes(15))
        // Too short, unreadable or not a string: the shortest allowed, so a mistyped value during a
        // planned move still refreshes soon, and nothing can make installations hammer the site.
        assertThat(refresh("\"PT1M\"")).isEqualTo(DiscoveredEndpoints.MIN_REFRESH)
        assertThat(refresh("\"-PT1H\"")).isEqualTo(DiscoveredEndpoints.MIN_REFRESH)
        assertThat(refresh("\"soon\"")).isEqualTo(DiscoveredEndpoints.MIN_REFRESH)
        assertThat(refresh("900")).isEqualTo(DiscoveredEndpoints.MIN_REFRESH)
        // Too long: capped at a day, so every installation looks again at least daily.
        assertThat(refresh("\"P2D\"")).isEqualTo(Duration.ofDays(1))
        assertThat(refresh("\"P365D\"")).isEqualTo(Duration.ofDays(1))
    }

    @Test
    fun `an out-of-range refreshAfter is reported to the operator`() {
        assertThat(parse("""{ "schemaVersion": 1, "refreshAfter": "soon" }""").rejected).anyMatch { it.startsWith("refreshAfter") }
        assertThat(parse("""{ "schemaVersion": 1, "refreshAfter": "PT1H" }""").rejected).isEmpty()
    }
}
