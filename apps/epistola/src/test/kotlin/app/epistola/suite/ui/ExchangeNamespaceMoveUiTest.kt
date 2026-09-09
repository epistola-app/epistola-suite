// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.exchange.CatalogPublicationWorker
import app.epistola.suite.exchange.CompleteExchangeConnection
import app.epistola.suite.exchange.SetCatalogPublicationNamespace
import app.epistola.suite.exchange.StartExchangeConnection
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.FakeExchangeServer
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Moving a published catalog to another namespace has consequences worth stating, but only for
 * someone who is actually moving it. Opening the same dialog to change the release policy should
 * not confront them with a warning about something they are not doing.
 */
class ExchangeNamespaceMoveUiTest : BasePlaywrightTest() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Test
    fun `the move warning appears only once the namespace is actually changed`() {
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
            CreateCatalog(tenant.id, catalogKey, "Invoices").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            // Exchange *accepting* a release is what makes the namespace consequential to change:
            // a submission it takes and then rejects has published nothing.
            worker.run()
        }

        gotoAndReady("/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/browse")
        val dialog = page.openDialogByTrigger(
            page.locator("button[hx-get*='section=publication'], button[data-hx-get*='section=publication']").first(),
            "#catalog-metadata-dialog",
        )

        val warning = dialog.locator(".alert-warning")
        val acknowledgement = dialog.locator("input[name='acknowledgeAlreadyPublished']")

        // Opened to change the policy: nothing is being moved, so nothing warns.
        assertThat(warning).isHidden()

        dialog.locator("select[name='chosenNamespace']").selectOption("internal-forms")
        assertThat(warning).isVisible()
        assertThat(acknowledgement).isVisible()

        // Put it back and the warning goes with it.
        dialog.locator("select[name='chosenNamespace']").selectOption("public-services")
        assertThat(warning).isHidden()
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
            registry.add("epistola.exchange.base-url") { exchange.baseUrl }
            registry.add("epistola.exchange.allow-http") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun stopExchange() = exchange.close()
    }
}
