// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.SetCatalogPublicationNamespace
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap

/**
 * Server-contract assertions for the Exchange settings page, made against the rendered response
 * rather than the template source, so they keep holding if the markup is reorganized.
 */
class ExchangeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    /**
     * One stand-in Exchange is shared by the whole class, so a test that changes what it grants
     * would otherwise decide what every later test sees.
     */
    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `an unconnected tenant is offered authorization as a top-level navigation`() {
        val tenant = createTenant("Exchange Page")
        enablePublishing(tenant)

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Connect to Exchange")

        // Authorization leaves our origin, so these forms must not be turned into an HTMX
        // background request — the browser has to follow the redirect itself.
        val connectForms = Regex("""<form\b[^>]*exchange/connect[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(requireNotNull(response.body)).map { it.value }.toList()
        assertThat(connectForms).isNotEmpty()
        assertThat(connectForms).allMatch { """hx-boost="false"""" in it }
    }

    @Test
    fun `the settings page reports publication activity across every catalog`() {
        val tenant = createTenant("Exchange Activity")
        withMediator {
            // A release only queues once its catalog has somewhere to publish, so the page can only
            // show activity for a tenant that is actually enrolled.
            enroll(tenant)
            listOf("activity-alpha", "activity-beta").forEach { slug ->
                val key = CatalogKey.of(slug)
                CreateCatalog(tenant.id, key, slug).execute()
                SetCatalogPublicationNamespace(tenant.id, key, "public-services").execute()
                ReleaseCatalogVersion(tenant.id, key, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            }
        }

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Publication activity")
        // Both catalogs on one page — this is what the per-catalog history cannot show.
        assertThat(response.body).contains("activity-alpha")
        assertThat(response.body).contains("activity-beta")
        assertThat(response.body).contains("Ready to submit")
    }

    @Test
    fun `the catalog page names a release that can no longer be published`() {
        val tenant = createTenant("Exchange Drifted")
        val catalogKey = CatalogKey.of("drifted")
        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Drifted").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.SKIP).execute()
            UpdateCatalogMetadata(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                name = "Drifted",
                description = "Changed after releasing",
                attributes = emptyList(),
            ).execute()
        }

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/browse",
            String::class.java,
        )

        // Asserted on the rendered page, not the query: a state field nothing renders explains nothing.
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("v1.0.0 can no longer be published")
        assertThat(response.body).contains("Release the current state as a new version")
    }

    /** Connecting requires the tenant feature as well as the deployment gate. */
    private fun enablePublishing(tenant: Tenant) = withMediator {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
    }

    /** Completes enrollment against the stand-in Exchange, from inside a mediator scope. */
    private fun enroll(tenant: Tenant) {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
        CompleteExchangeConnection(
            tenant.id,
            requireNotNull(exchange.latestState.get()),
            "authorization-code",
            FakeExchangeServer.OAUTH_APPLICATION_ID,
            exchange.baseUrl,
        ).execute()
    }

    companion object {
        private val exchange = FakeExchangeServer()

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }

    /**
     * The release dialog must never take an instruction it cannot carry out. Ticking "publish"
     * makes the namespace field required, and with nothing granted that field has no options — so
     * the form simply refuses to submit, saying only "please select an item in the list".
     */
    @Test
    fun `a release cannot be sent to Exchange when the tenant is not connected`() {
        val tenant = createTenant("Release No Connection")
        val catalogKey = CatalogKey.of("no-connection")
        enablePublishing(tenant)
        withMediator { CreateCatalog(tenant.id, catalogKey, "No connection").execute() }

        val body = releaseDialog(tenant, catalogKey)

        // The instruction is shown as unavailable rather than offered and then refused.
        assertThat(body).contains("Publish this version to Epistola Exchange")
        assertThat(body).doesNotContain("""name="publishToExchange"""")
        assertThat(body).doesNotContain("""name="chosenNamespace"""")
        assertThat(body).contains("not connected to Epistola Exchange")
        // Releasing locally is a separate act and is unaffected.
        assertThat(body).contains("this version is created either way")
    }

    @Test
    fun `a release cannot be sent to Exchange when the organization grants no namespace`() {
        val tenant = createTenant("Release No Namespace")
        val catalogKey = CatalogKey.of("no-namespace")
        exchange.namespaces = emptyList()
        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "No namespace").execute()
        }

        val body = releaseDialog(tenant, catalogKey)

        assertThat(body).doesNotContain("""name="publishToExchange"""")
        assertThat(body).doesNotContain("""name="chosenNamespace"""")
        // Connected, so the fix is not "connect" — it is the organization's to make.
        assertThat(body).doesNotContain("not connected to Epistola Exchange")
        assertThat(body).contains("has not granted it a namespace")
    }

    @Test
    fun `a release is offered to Exchange once there is somewhere for it to go`() {
        val tenant = createTenant("Release Publishable")
        val catalogKey = CatalogKey.of("publishable")
        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Publishable").execute()
        }

        val body = releaseDialog(tenant, catalogKey)

        assertThat(body).contains("""name="publishToExchange"""")
        assertThat(body).contains("""name="chosenNamespace"""")
        assertThat(body).contains("public-services")
        assertThat(body).doesNotContain("not connected to Epistola Exchange")
    }

    private fun releaseDialog(tenant: Tenant, catalogKey: CatalogKey): String {
        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/release",
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body)
    }

    @Test
    fun `a malformed authorization callback is a bad request, not a server error`() {
        val response = restTemplate.getForEntity(
            "/oauth/exchange/callback?state=unknown-state&code=x&client_id=not-a-uuid&iss=https://exchange.example",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a rejected setup action is shown on the settings page, not as an error page`() {
        val tenant = createTenant("Exchange Bad Namespace")
        enablePublishing(tenant)

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply { add("namespace", "not-granted") }
        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/exchange/namespace",
            HttpEntity(body, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("not available to this Exchange connection")
        assertThat(response.body).contains("Connect to Exchange")
    }
}
