// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.namespaces.GrantTenantNamespace
import app.epistola.suite.catalog.namespaces.ListNamespaceGrants
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceGrantSource
import app.epistola.suite.catalog.namespaces.NamespaceName
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
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The Exchange connection grants its namespaces (ADR 0034, section 3), and a catalog named under
 * `local` never reaches the Exchange, whatever the connection grants (section 7).
 */
class ExchangeNamespaceGrantsTest : ExchangeIntegrationTestBase() {

    private fun tenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("nsexchange-${TestTenantCounter.next("nsexchange")}"), name, namespaces.toList()).execute().id
    }

    private fun enroll(tenant: TenantKey) {
        SaveFeatureToggle(tenant, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant, CALLBACK).execute()
        CompleteExchangeConnection(tenant, requireNotNull(exchange.latestState.get()), "authorization-code", FakeExchangeServer.OAUTH_APPLICATION_ID, exchange.baseUrl).execute()
    }

    @Test
    fun `connecting grants the namespaces the connection grants, beside the tenant's own, and not one held under another authority`() {
        val tenant = tenant("Exchange grants", NamespaceName("gemeente-x.nl", "internal-forms"))
        exchange.namespaces = listOf("public-services", "internal-forms")

        withMediator {
            enroll(tenant)

            val grants = ListNamespaceGrants(tenant).query()
            assertThat(grants.map { it.name to it.grantedThrough }).containsExactly(
                NamespaceName("gemeente-x.nl", "internal-forms") to NamespaceGrantSource.ADMINISTRATOR,
                NamespaceName("exchange.epistola.app", "public-services") to NamespaceGrantSource.EXCHANGE_CONNECTION,
            )
            assertThat(grants.single { it.namespace == "public-services" }.grantedBy).isNull()

            // A new catalog may be named in the granted namespace, and a release there is the tenant's to issue.
            val catalog = CatalogKey.of("services")
            CreateCatalog(tenant, catalog, "Services", namespace = "public-services").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant))), "Brand").execute()
            MarkCatalogReady(tenant, catalog).execute()
            ReleaseCatalogDraft(tenant, catalog, "1.0.0", publication = ReleasePublication.SKIP).execute()

            // Reauthorizing grants nothing twice.
            enroll(tenant)
            assertThat(ListNamespaceGrants(tenant).query()).hasSize(2)
        }
    }

    @Test
    fun `a catalog under local is not published to the Exchange, even in a namespace the connection grants`() {
        val tenant = tenant("Exchange local")
        exchange.namespaces = listOf("public-services")
        val catalog = CatalogKey.of("local-letters")

        withMediator {
            // Named under local before the connection granted the same token.
            CreateCatalog(tenant, catalog, "Local letters", registry = LocalAuthority.NAME, namespace = "public-services").execute()
            enroll(tenant)
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant))), "Brand").execute()
            MarkCatalogReady(tenant, catalog).execute()

            // Releasing does not queue it for the Exchange, though publishing is asked for.
            val released = ReleaseCatalogDraft(tenant, catalog, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            assertThat(released.publicationId).isNull()
            assertThat(GetCatalogPublicationState(tenant, catalog).query()?.publications.orEmpty()).isEmpty()

            assertThatThrownBy { PublishCurrentCatalogRelease(tenant, catalog, "1.0.0").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_LOCAL)
                    assertThat(it.message).isEqualTo("local/public-services/local-letters is named under local, which never leaves this organisation, so it is not published to Epistola Exchange.")
                }
        }
    }

    @Test
    fun `an administrator's grant of a namespace stands beside the connection's`() {
        val tenant = tenant("Exchange and administrator")
        exchange.namespaces = listOf("public-services")

        withMediator {
            enroll(tenant)
            GrantTenantNamespace(tenant, "gemeente-x.nl", "acme").execute()
            assertThat(ListNamespaceGrants(tenant).query().map { it.namespace }).containsExactly("acme", "public-services")
        }
    }

    companion object {
        private const val CALLBACK = "https://suite.example/oauth/exchange/callback"
    }
}
