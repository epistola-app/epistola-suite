// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.exchange.CatalogPublicationWorker
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.testCatalog
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * A catalog's namespace is part of its name on Exchange, so once Exchange accepted a release under it
 * there is no moving it (`CatalogNamespaceFixedException`). The publication dialog says so instead
 * of offering a picker that could only be refused.
 */
class ExchangeNamespaceMoveUiTest : BasePlaywrightTest() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Test
    fun `once Exchange accepted a release the namespace is shown as fixed, with nothing to change it`() {
        val tenant = createTenant("Move Warning")
        val catalogKey = CatalogKey.of("invoices")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant.id, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(
                tenant.id,
                requireNotNull(exchange.latestState.get()),
                "authorization-code",
                FakeExchangeServer.OAUTH_APPLICATION_ID,
                exchange.baseUrl,
            ).execute()
            // The tenant holds several namespaces through its Exchange connection, so a catalog names one.
            CreateCatalog(tenant.id, catalogKey, "Invoices", namespace = "public-services").execute()
            MarkCatalogReady(testCatalog(tenant.id, catalogKey)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, catalogKey), "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            // Exchange *accepting* a release is what makes the namespace consequential to change:
            // a submission it takes and then rejects has published nothing.
            worker.run()
        }

        // Exchange publication is on the catalog's Settings page.
        gotoAndReady("/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/settings")
        val dialog = page.openDialogByTrigger(
            page.locator("button[hx-get*='section=publication'], button[data-hx-get*='section=publication']").first(),
            "#catalog-metadata-dialog",
        )

        assertThat(dialog.getByTestId("catalog-namespace-fixed")).containsText("public-services")
        assertThat(dialog.locator("select[name='chosenNamespace']")).hasCount(0)
    }

    companion object {
        private val exchange = FakeExchangeServer().apply {
            namespaces = listOf("public-services", "internal-forms")
            submitResponse = { FakeExchangeServer.Response(200, publicationBody(remotePublicationId, "ACCEPTED")) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun exchangeProperties(registry: DynamicPropertyRegistry) {
            registry.add("epistola.exchange.enabled") { "true" }
            registry.add("epistola.exchange.endpoint") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
