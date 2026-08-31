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
import app.epistola.suite.exchange.CatalogPublicationWorker
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
import java.time.Duration

/**
 * Server-contract assertions for the Exchange settings page, made against the rendered response
 * rather than the template source, so they keep holding if the markup is reorganized.
 */
class ExchangeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Autowired
    private lateinit var credentials: app.epistola.suite.exchange.ExchangeCredentialService

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

    /**
     * Credentials Exchange will not accept are a state to recover from, so the page has to say what
     * to do about it. The transport's own words — `401 Unauthorized: "{"error":"invalid_client"}"` —
     * were stored verbatim and shown as the whole explanation, which is accurate and unusable.
     */
    @Test
    fun `credentials Exchange refuses are explained in terms of what to do`() {
        val tenant = createTenant("Exchange Rejected Credentials")
        withMediator { enroll(tenant) }
        // Exchange no longer knows this application at all — what a rebuilt Exchange looks like.
        exchange.tokenResponse = { FakeExchangeServer.Response(401, """{"error":"invalid_client"}""") }

        withMediator { credentials.accessToken(requireNotNull(credentials.connection(tenant.id)), Duration.ofDays(365)) }

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The state leads, and it says what to do about it.
        assertThat(response.body).contains("Reauthorization required")
        assertThat(response.body).contains("Reconnecting restores the same connection")
        assertThat(response.body).contains("Recover Exchange connection")
        // What the call reported is kept, but as supporting detail rather than the explanation.
        assertThat(response.body).contains("Exchange reported:")
        assertThat(response.body).contains("Exchange no longer recognises this installation")
    }

    /**
     * Whatever a failed call reported, the page has to read as guidance. Errors recorded before this
     * page knew how to present them — or by any future path that stores a transport message — must
     * not become the headline again.
     */
    @Test
    fun `a raw error recorded earlier is presented as detail, not as the explanation`() {
        val tenant = createTenant("Exchange Raw Error")
        withMediator { enroll(tenant) }
        // Exactly what the old code stored, and what a rebuilt Exchange produced.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE exchange_tenant_connections
                SET status = 'REAUTHORIZATION_REQUIRED', last_error = :error WHERE tenant_key = :tenantKey
                """,
            ).bind("error", "401 Unauthorized: {\"error\":\"invalid_client\"}").bind("tenantKey", tenant.id).execute()
        }

        val body = requireNotNull(
            restTemplate.getForEntity("/tenants/${tenant.id.value}/exchange", String::class.java).body,
        )

        assertThat(body).contains("Reauthorization required")
        assertThat(body).contains("Reconnecting restores the same connection")
        // Kept and labelled, rather than shown alone as though it were the explanation.
        assertThat(body).contains("Exchange reported:")
    }

    /**
     * A release that is not progressing has to say so where it was published from.
     *
     * The tenant-wide settings page already warned, but the catalog page — where an author is
     * standing after pressing publish — showed an in-progress badge and nothing else, for the
     * twenty-four hours it takes to give up on a submission Exchange never decides.
     */
    @Test
    fun `a release that has been waiting too long says so on the catalog page`() {
        val tenant = createTenant("Exchange Stalled")
        val catalogKey = CatalogKey.of("stalled")
        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Stalled").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
        }

        assertThat(catalogPage(tenant, catalogKey)).doesNotContain("waiting to publish for over an hour")

        // `created_at` is written and aged by the database, so the test clock cannot reach it;
        // planting the historical timestamp is the documented exception.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalog_release_publications SET created_at = NOW() - INTERVAL '3 hours' WHERE tenant_key = :tenantKey",
            ).bind("tenantKey", tenant.id).execute()
        }

        assertThat(catalogPage(tenant, catalogKey)).contains("A release has been waiting to publish for over an hour")
    }

    private fun catalogPage(tenant: Tenant, catalogKey: CatalogKey): String {
        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/browse",
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body)
    }

    /**
     * Exchange is the only side that knows what became of a submission, so the catalog page links
     * straight to it — and links to the *submission*, which exists in every state, rather than the
     * release, which exists only once one was published. A stuck or refused publication is exactly
     * when someone goes looking.
     */
    @Test
    fun `a submitted publication links to itself on Exchange`() {
        val tenant = createTenant("Exchange Deep Link")
        val catalogKey = CatalogKey.of("deep-link")
        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Deep link").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            // Only once Exchange has taken it does a page exist there to link to.
            worker.run()
        }

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/browse",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("View on Exchange")
        assertThat(response.body)
            .contains("${exchange.baseUrl}/organizations/acme/publishing/${exchange.remotePublicationId}")
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
