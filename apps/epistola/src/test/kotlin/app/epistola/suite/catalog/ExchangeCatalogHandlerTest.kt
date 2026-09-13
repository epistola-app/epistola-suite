// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.InstallExchangeCatalog
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.handlers.ExchangeHandlerTestBase
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
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
 * The browse-and-install surface, at the handler level.
 *
 * Preferred over a browser test for these, per the deterministic-only UI-test philosophy: what is
 * being asserted is the server contract — which fragment comes back, what it says, and that a
 * refusal arrives as a refusal rather than an error page.
 */
class ExchangeCatalogHandlerTest : ExchangeHandlerTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun htmxGet() = HttpHeaders().apply { add("HX-Request", "true") }

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    @Test
    fun `the browse page lists what Exchange offers`() {
        val tenant = connectedTenant("browse-page")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0", name = "Invoices")

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Invoices")
        assertThat(response.body).contains("acme/invoices")
        assertThat(response.body).contains("v1.0.0")
    }

    @Test
    fun `a tenant that is not connected is told how to connect rather than shown an error`() {
        val tenant = featureOnlyTenant("browse-not-connected")

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs/exchange", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("exchange-unavailable")
        assertThat(response.body).contains("Connect this tenant")
    }

    /**
     * The dialog is swapped into a container and opened by `behaviors.js`, which reacts to
     * `data-dialog-mount` on the *container*. `data-open-dialog` is a click trigger for a button
     * and does nothing on the dialog itself — getting that wrong swaps a dialog in that never
     * becomes visible, which is invisible to every assertion about the fragment's content.
     */
    @Test
    fun `the browse page mounts dialogs where behaviors js will open them`() {
        val tenant = connectedTenant("dialog-mount")

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs/exchange", String::class.java)

        assertThat(response.body).contains("id=\"exchange-dialog-container\" data-dialog-mount")
    }

    @Test
    fun `the install dialog offers only releases that can actually be installed`() {
        val tenant = connectedTenant("detail-dialog")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "2.0.0"), version = "2.0.0", availability = "WITHDRAWN")

        val response = restTemplate.exchange(
            "/tenants/$tenant/catalogs/exchange/acme/invoices",
            HttpMethod.GET,
            HttpEntity<Void>(htmxGet()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("exchange-install-dialog")
        assertThat(response.body).contains("v1.0.0")
        // Exchange still returns the withdrawn release to a tenant that publishes; it must not be
        // offered as something to install.
        assertThat(response.body).doesNotContain("value=\"2.0.0\"")
    }

    @Test
    fun `installing closes the dialog and refreshes the list`() {
        val tenant = connectedTenant("install-ok")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")

        val response = restTemplate.postForEntity(
            "/tenants/$tenant/catalogs/exchange/acme/invoices/install",
            HttpEntity(LinkedMultiValueMap<String, String>().apply { add("version", "1.0.0") }, htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("catalog-list")
    }

    @Test
    fun `a colliding catalog id is refused inside the dialog, not as an error page`() {
        val tenant = connectedTenant("install-collision")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        exchange.publish("globex", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        withMediator { InstallExchangeCatalog(tenant, "acme", "invoices").execute() }

        val response = restTemplate.postForEntity(
            "/tenants/$tenant/catalogs/exchange/globex/invoices/install",
            HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm()),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.body).contains("exchange-install-error")
        assertThat(response.body).contains("acme/invoices")
    }

    /**
     * Re-opening the dialog for a catalog already installed from the same coordinates is a
     * re-install or an upgrade, not a collision. Treating any same-named catalog as a clash meant a
     * failed first attempt left a row that made its own retry look like somebody else's catalog.
     */
    @Test
    fun `the dialog does not call an already-installed catalog a collision`() {
        val tenant = connectedTenant("dialog-reinstall")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        withMediator { InstallExchangeCatalog(tenant, "acme", "invoices").execute() }

        val response = restTemplate.exchange(
            "/tenants/$tenant/catalogs/exchange/acme/invoices",
            HttpMethod.GET,
            HttpEntity<Void>(htmxGet()),
            String::class.java,
        )

        assertThat(response.body).doesNotContain("exchange-key-taken")
        assertThat(response.body).contains("exchange-install-submit")
    }

    /**
     * The assertion that proves the persisted-state design: the badge is there on first paint,
     * with no outbound call made while the page rendered.
     */
    @Test
    fun `the catalogs list shows an available update without calling Exchange`() {
        val tenant = connectedTenant("list-badge")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        withMediator { InstallExchangeCatalog(tenant, "acme", "invoices").execute() }
        // A newer release exists and the recorded state knows it, as the background check would.
        recordAvailable(tenant, "2.0.0")
        exchange.catalogLookups.clear()

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("catalog-upgrade-review")
        assertThat(response.body).contains("v2.0.0")
        assertThat(exchange.catalogLookups).isEmpty()
    }

    @Test
    fun `the update dialog for an Exchange catalog offers its releases`() {
        val tenant = connectedTenant("upgrade-dialog")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        withMediator { InstallExchangeCatalog(tenant, "acme", "invoices").execute() }
        exchange.publish("acme", "invoices", releaseArchive("invoices", "2.0.0"), version = "2.0.0")

        val response = restTemplate.exchange(
            "/tenants/$tenant/catalogs/invoices/exchange-upgrade",
            HttpMethod.GET,
            HttpEntity<Void>(htmxGet()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("exchange-upgrade-dialog")
        assertThat(response.body).contains("v2.0.0")
        // The guarantee is stated where the decision is made, not only in a commit message.
        assertThat(response.body).containsIgnoringCase("nothing is")
    }

    private fun recordAvailable(tenant: TenantKey, version: String) {
        // Raw SQL: there is no command for "the background check found a newer release", and
        // running the worker would need Exchange to be asked, which is what this test excludes.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalog_upstream_checks SET available_release_version = :v WHERE tenant_key = :t",
            ).bind("v", version).bind("t", tenant).execute()
        }
    }

    private fun featureOnlyTenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator { SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute() }
        return tenant
    }

    /**
     * A catalog installed from Exchange has a page there; a mirrored resource has none here.
     *
     * The browse page renders subscribed resources as plain text, because there is nothing local to
     * open. When the catalog came from Exchange there is something to open, and the link is
     * constructible from what is already known: the namespace and key live in `catalogs.source_url`,
     * and Exchange's own route is /catalogs/{namespace}/{key}/resources/{type}/{slug}. Until
     * epistola-exchange#8 exposes a release's resource inventory over its API, this is the whole of
     * what a Suite can say about a resource it has not downloaded.
     */
    @Test
    fun `resources of an Exchange catalog link to their page on Exchange`() {
        val tenant = connectedTenant("resource-links")
        exchange.publish("acme", "invoices", releaseArchive("invoices", "1.0.0"), version = "1.0.0")
        withMediator { InstallExchangeCatalog(tenant, "acme", "invoices").execute() }

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs/invoices/browse", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = requireNotNull(response.body)
        // The catalog's own page, from the Source line.
        assertThat(body).contains("${exchange.baseUrl}/catalogs/acme/invoices")
        // And a per-resource page. releaseArchive publishes a catalog carrying one theme.
        assertThat(body).contains("${exchange.baseUrl}/catalogs/acme/invoices/resources/theme/")
        // Leaving the application, so never swapped into the page by hx-boost.
        assertThat(body).contains("hx-boost=\"false\"")
    }

    /**
     * A subscribed catalog that did not come from Exchange has no page to link to, so the rows stay
     * plain text rather than pointing at a guess.
     */
    @Test
    fun `resources of a URL-subscribed catalog are not linked to Exchange`() {
        val tenant = connectedTenant("no-exchange-links")
        withMediator {
            RegisterCatalog(
                tenantKey = tenant,
                sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json",
                authType = AuthType.NONE,
            ).execute()
        }

        val response = restTemplate.getForEntity("/tenants/$tenant/catalogs/epistola-demo/browse", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requireNotNull(response.body)).doesNotContain("/catalogs/acme/")
        assertThat(response.body).doesNotContain("${exchange.baseUrl}/catalogs/")
    }

    private fun connectedTenant(name: String): TenantKey {
        val tenant = featureOnlyTenant(name)
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(
                tenant,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
        }
        return tenant
    }

    private fun releaseArchive(slug: String, version: String): ByteArray {
        val publisher = createTenant("handler-publisher-$slug-$version").id
        val catalogKey = CatalogKey.of(slug)
        return withMediator {
            CreateCatalog(publisher, catalogKey, "Invoices").execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher))),
                name = "Theme $version",
            ).execute()
            ReleaseCatalogVersion(tenantKey = publisher, catalogKey = catalogKey, version = version).execute()
            ExportCatalogZip(tenantKey = publisher, catalogKey = catalogKey).execute().zipBytes
        }
    }
}
