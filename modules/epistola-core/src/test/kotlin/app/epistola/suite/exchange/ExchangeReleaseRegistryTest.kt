// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.registry.InstallReleaseRegistryRelease
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.catalog.registry.ListReleaseRegistryReleases
import app.epistola.suite.catalog.registry.NamespaceNotGrantedException
import app.epistola.suite.catalog.registry.PublishReleaseToReleaseRegistry
import app.epistola.suite.catalog.registry.RegistryReleaseNotFoundException
import app.epistola.suite.catalog.registry.ReleaseRegistryKind
import app.epistola.suite.catalog.registry.ReleaseRegistryNotFoundException
import app.epistola.suite.catalog.registry.ReleaseRegistryPublication
import app.epistola.suite.catalog.registry.ReleaseRegistrySummary
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The Epistola Exchange as a release registry (contract ADR 0008, kind `exchange`): listed for a
 * connected tenant, its releases listed and installed, and a release published to it queued.
 */
class ExchangeReleaseRegistryTest : ExchangeIntegrationTestBase() {

    @Test
    fun `a tenant has the Exchange as a release registry only once it is connected`() {
        val unconnected = createTenant("Registry Unconnected").id
        val connected = connectedTenant("Registry Connected")
        withMediator {
            assertThat(ListReleaseRegistries(unconnected).query().registries).isEmpty()
            assertThatThrownBy { ListReleaseRegistryReleases(unconnected, "exchange", "acme", "invoices").query() }
                .isInstanceOf(ReleaseRegistryNotFoundException::class.java)

            assertThat(ListReleaseRegistries(connected).query().registries)
                .containsExactly(ReleaseRegistrySummary("exchange", ReleaseRegistryKind.EXCHANGE, exchange.baseUrl))
        }
    }

    @Test
    fun `its releases are listed highest first, installed as an import, and then present`() {
        val consumer = connectedTenant("Registry Consumer")
        exchange.publish("acme", "registry-letters", releaseArchive("registry-letters", "1.0.0"), version = "1.0.0")
        exchange.publish("acme", "registry-letters", releaseArchive("registry-letters", "1.1.0"), version = "1.1.0")

        withMediator {
            val listed = ListReleaseRegistryReleases(consumer, "exchange", "acme", "registry-letters").query()
            assertThat(listed.kind).isEqualTo(ReleaseRegistryKind.EXCHANGE)
            assertThat(listed.releases.map { it.version to it.present }).containsExactly("1.1.0" to false, "1.0.0" to false)

            val installed = InstallReleaseRegistryRelease(consumer, "exchange", "acme", "registry-letters", "1.0.0").execute()
            assertThat(installed.newlyInstalled).isTrue()
            assertThat(ListReleaseRegistryReleases(consumer, "exchange", "acme", "registry-letters").query().releases.map { it.version to it.present })
                .containsExactly("1.1.0" to false, "1.0.0" to true)

            assertThatThrownBy { InstallReleaseRegistryRelease(consumer, "exchange", "acme", "registry-letters", "9.0.0").execute() }
                .isInstanceOf(RegistryReleaseNotFoundException::class.java)
        }
    }

    @Test
    fun `publishing to it queues the release, again too, and a namespace it does not grant is refused`() {
        val tenant = connectedTenant("Registry Publisher")
        val granted = CatalogKey.of("registry-granted")
        val other = CatalogKey.of("registry-other")
        withMediator {
            CreateCatalog(tenant, granted, "Granted", namespace = TestNamespaces.of(tenant).namespace).execute()
            ChangeCatalogNamespace(tenant, granted, "public-services").execute()
            ReleaseCatalogDraft(tenant, granted, "1.0.0", publication = ReleasePublication.SKIP).execute()

            val first = PublishReleaseToReleaseRegistry(tenant, granted, "1.0.0", "exchange").execute()
            assertThat(first.status).isEqualTo(ReleaseRegistryPublication.Status.QUEUED)
            assertThat(first.kind).isEqualTo(ReleaseRegistryKind.EXCHANGE)
            assertThat(PublishReleaseToReleaseRegistry(tenant, granted, "1.0.0", "exchange").execute().status).isEqualTo(ReleaseRegistryPublication.Status.QUEUED)
            assertThat(GetCatalogPublicationState(tenant, granted).query()!!.publications).hasSize(1)

            // Held here, by an administrator's grant, but not granted by the Exchange connection.
            TestNamespaces.grant(tenant, "not-granted").execute()
            CreateCatalog(tenant, other, "Other", namespace = "not-granted").execute()
            ReleaseCatalogDraft(tenant, other, "1.0.0", publication = ReleasePublication.SKIP).execute()
            assertThatThrownBy { PublishReleaseToReleaseRegistry(tenant, other, "1.0.0", "exchange").execute() }
                .isInstanceOf(NamespaceNotGrantedException::class.java)
        }
    }

    private fun connectedTenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_INSTALLING, true).execute()
            SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            StartExchangeConnection(tenant, "https://suite.example/oauth/exchange/callback").execute()
            CompleteExchangeConnection(tenant, requireNotNull(exchange.latestState.get()), "authorization-code", FakeExchangeServer.OAUTH_APPLICATION_ID, exchange.baseUrl).execute()
        }
        return tenant
    }

    /** Release [version] of [slug] in namespace `acme`, as its publisher exports it. */
    private fun releaseArchive(slug: String, version: String): ByteArray {
        val publisher = createTenant("publisher-$slug-$version").id
        val catalogKey = CatalogKey.of(slug)
        return withMediator {
            TestNamespaces.grant(publisher, "acme").execute()
            CreateCatalog(publisher, catalogKey, "Letters", namespace = "acme").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("thm"), CatalogId(catalogKey, TenantId(publisher))), name = "Theme $version").execute()
            MarkCatalogReady(publisher, catalogKey).execute()
            ReleaseCatalogDraft(tenantKey = publisher, catalogKey = catalogKey, version = version).execute()
            mediator.exportReleased(CatalogId(catalogKey, TenantId(publisher))).zipBytes
        }
    }
}
