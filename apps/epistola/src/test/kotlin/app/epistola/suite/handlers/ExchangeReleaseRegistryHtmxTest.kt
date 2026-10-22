// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.GetCatalogPublicationState
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeExchangeServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The registry dialogs offer the Epistola Exchange a tenant is connected to as a release registry
 * (contract ADR 0008), beside any container registry; without a connection there is none.
 */
class ExchangeReleaseRegistryHtmxTest : ExchangeHandlerTestBase() {

    @Autowired private lateinit var restTemplate: TestRestTemplate

    private fun htmx() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun get(url: String) = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(htmx()), String::class.java)

    @Test
    fun `an unconnected tenant is offered no registry, a connected one the Exchange`() {
        val unconnected = createTenant("Exchange Registry None").id
        assertThat(restTemplate.getForEntity("/tenants/${unconnected.value}/catalogs", String::class.java).body)
            .doesNotContain("data-testid=\"catalog-registry-open\"")
        assertThat(get("/tenants/${unconnected.value}/catalogs/registry").body).contains("data-testid=\"registry-none\"")

        val tenant = connected("Exchange Registry Dialog")
        assertThat(restTemplate.getForEntity("/tenants/${tenant.value}/catalogs", String::class.java).body)
            .contains("data-testid=\"catalog-registry-open\"")
        val dialog = get("/tenants/${tenant.value}/catalogs/registry")
        assertThat(dialog.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(dialog.body).contains("name=\"registry\" value=\"exchange\"", exchange.baseUrl).doesNotContain("data-testid=\"registry-none\"")
    }

    @Test
    fun `a release published to the Exchange from its page is queued`() {
        val tenant = connected("Exchange Registry Publish")
        val catalog = CatalogKey.of("ui-exchange-publish")
        withMediator {
            CreateCatalog(tenant, catalog, "UI exchange publish").execute()
            ChangeCatalogNamespace(tenant, catalog, "public-services").execute()
            ReleaseCatalogDraft(tenant, catalog, "1.0.0", publication = ReleasePublication.SKIP).execute()
        }

        val page = restTemplate.getForEntity("/tenants/${tenant.value}/catalogs/${catalog.value}/releases/1.0.0", String::class.java).body!!
        assertThat(page).contains("/catalogs/${catalog.value}/registry-publish")
        assertThat(get("/tenants/${tenant.value}/catalogs/${catalog.value}/registry-publish?version=1.0.0").body).contains("name=\"registry\" value=\"exchange\"")

        val form = LinkedMultiValueMap<String, String>().apply {
            add("version", "1.0.0")
            add("registry", "exchange")
        }
        val published = restTemplate.postForEntity(
            "/tenants/${tenant.value}/catalogs/${catalog.value}/registry-publish",
            HttpEntity(form, htmx().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }),
            String::class.java,
        )
        assertThat(published.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(published.body).contains("data-testid=\"registry-queued\"")
        assertThat(withMediator { GetCatalogPublicationState(tenant, catalog).query()!!.publications }).hasSize(1)
    }

    private fun connected(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute()
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(tenant, requireNotNull(exchange.latestState.get()), "authorization-code", FakeExchangeServer.OAUTH_APPLICATION_ID, exchange.baseUrl).execute()
        }
        return tenant
    }
}
