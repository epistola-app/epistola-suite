// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.CatalogUpstreamCheckWorker
import app.epistola.suite.catalog.UpstreamAvailability
import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Finding out that a newer release exists, without anybody looking at the catalogs page.
 *
 * The cadence is what these mostly pin. A check that costs two requests where one would do, or that
 * re-asks a source it just asked, is not wrong so much as rude — and it is rude once per catalog
 * per interval, for ever.
 */
class ExchangeUpstreamCheckTest : IntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogUpstreamCheckWorker

    @Autowired
    private lateinit var store: CatalogUpstreamCheckStore

    @Autowired
    private lateinit var jdbi: Jdbi

    private val catalogKey = CatalogKey.of("invoices")

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `a newer release is recorded without anyone asking for it`() {
        val tenant = installed("check-newer")
        exchange.publish("acme", "invoices", releaseArchive("2.0.0"), version = "2.0.0")
        makeDue(tenant)

        worker.run()

        val state = store.stateFor(tenant, catalogKey)!!
        assertThat(state.availableVersion).isEqualTo("2.0.0")
        assertThat(state.upgradeAvailable("1.0.0")).isTrue()
        assertThat(state.failure).isNull()
    }

    @Test
    fun `an unchanged catalog costs exactly one request`() {
        val tenant = installed("check-unchanged")
        makeDue(tenant)
        exchange.catalogLookups.clear()

        worker.run()

        // latestVersion already excludes releases nobody may install, so when it matches what is
        // installed there is nothing the release list could add.
        assertThat(exchange.catalogLookups).containsExactly("acme/invoices")
        assertThat(store.stateFor(tenant, catalogKey)!!.upgradeAvailable("1.0.0")).isFalse()
    }

    @Test
    fun `a catalog that is not due yet is left alone`() {
        val tenant = installed("check-not-due")
        exchange.publish("acme", "invoices", releaseArchive("2.0.0"), version = "2.0.0")
        exchange.catalogLookups.clear()

        // Installing scheduled the next check a day out; the worker must respect that rather than
        // asking again on the next tick.
        worker.run()

        assertThat(exchange.catalogLookups).isEmpty()
        assertThat(store.stateFor(tenant, catalogKey)!!.availableVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `an Exchange that answers with a failure is recorded, not escalated`() {
        val tenant = installed("check-unreachable")
        makeDue(tenant)
        exchange.catalogResponse = { FakeExchangeServer.Response(503, """{"title":"Service Unavailable"}""") }

        worker.run()

        val state = store.stateFor(tenant, catalogKey)!!
        assertThat(state.failure).isNotNull()
        // The catalog keeps the last answer it did get rather than losing it, so the page can still
        // say what it knew and when, instead of going blank because one check did not land.
        assertThat(state.availableVersion).isEqualTo("1.0.0")
    }

    @Test
    fun `a catalog Exchange no longer publishes says so`() {
        val tenant = installed("check-gone")
        makeDue(tenant)
        exchange.hostedCatalogs.clear()

        worker.run()

        assertThat(store.stateFor(tenant, catalogKey)!!.failure).isEqualTo(CatalogUpstreamCheckFailure.NOT_FOUND)
    }

    @Test
    fun `a withdrawn installed release is reported rather than acted on`() {
        val tenant = installed("check-withdrawn")
        // The publisher pulls the release this tenant is running and offers an older one instead.
        exchange.hostedCatalogs.clear()
        exchange.publish("acme", "invoices", releaseArchive("0.9.0"), version = "0.9.0")
        exchange.publish("acme", "invoices", releaseArchive("1.0.0"), version = "1.0.0", availability = "WITHDRAWN")
        makeDue(tenant)

        worker.run()

        val state = store.stateFor(tenant, catalogKey)!!
        assertThat(state.installedAvailability).isEqualTo(UpstreamAvailability.WITHDRAWN)
        // The way forward is the newest release still offered, even though it is older.
        assertThat(state.availableVersion).isEqualTo("0.9.0")
    }

    @Test
    fun `the check button answers now rather than waiting for the schedule`() {
        val tenant = installed("check-button")
        exchange.publish("acme", "invoices", releaseArchive("2.0.0"), version = "2.0.0")

        val state = withMediator { CheckCatalogUpstream(tenant, catalogKey).execute() }

        // Not due for hours, but asked for explicitly.
        assertThat(state!!.availableVersion).isEqualTo("2.0.0")
    }

    /** A connected tenant with `acme/invoices` 1.0.0 installed. */
    private fun installed(name: String): TenantKey {
        val tenant = createTenant(name).id
        exchange.publish("acme", "invoices", releaseArchive("1.0.0"), version = "1.0.0")
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute()
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(
                tenant,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
            InstallExchangeCatalog(tenant, "acme", "invoices").execute()
        }
        return tenant
    }

    /**
     * Brings a catalog's next check forward.
     *
     * Raw SQL because there is no command for "pretend six hours passed": the schedule is written by
     * the store from the database clock, which the test clock does not control.
     */
    private fun makeDue(tenant: TenantKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalog_upstream_checks SET next_check_at = NOW() - INTERVAL '1 minute' WHERE tenant_key = :t",
            ).bind("t", tenant).execute()
        }
    }

    private fun releaseArchive(version: String): ByteArray {
        val publisher = createTenant("upstream-publisher-$version").id
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
}
