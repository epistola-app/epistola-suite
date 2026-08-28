// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * What happens to a tenant's catalogs when the connection underneath them changes.
 *
 * A connection carries three things at once — credentials, the organization the tenant *is*, and the
 * namespaces it may publish into. The last two can change after catalogs have already bound to a
 * namespace, and a binding is deliberately hard to move. These cover the cases where that collides.
 */
class ExchangeConnectionLifecycleTest : IntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var credentials: ExchangeCredentialService

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun resetExchange() = exchange.reset()

    @Test
    fun `a reauthorization into a different organization is refused rather than silently adopted`() {
        val tenant = createTenant("exchange-org-change")

        withMediator {
            enroll(tenant)
            assertThat(credentials.connection(tenant.id)?.organizationSlug).isEqualTo("acme")

            // The same administrator authorizes again, but Exchange hands back a different org.
            exchange.organizationSlug = "some-other-org"
            StartExchangeConnection(tenant.id, CALLBACK).execute()
            assertThatThrownBy {
                CompleteExchangeConnection(
                    tenant.id,
                    requireNotNull(exchange.latestState.get()),
                    "authorization-code",
                    FakeExchangeServer.OAUTH_APPLICATION_ID,
                    exchange.baseUrl,
                ).execute()
            }.isInstanceOfSatisfying(ValidationException::class.java) {
                assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_ORGANIZATION_CHANGED)
            }

            // The tenant still belongs to the organization its catalogs are bound under.
            assertThat(credentials.connection(tenant.id)?.organizationSlug).isEqualTo("acme")
        }
    }

    @Test
    fun `a binding the connection no longer grants defers that publication instead of blocking the connection`() {
        val tenant = createTenant("exchange-grant-withdrawn")
        val catalogKey = CatalogKey.of("grant-withdrawn")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Grant withdrawn").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            assertThat(publication(tenant.id, catalogKey).namespace).isEqualTo("public-services")

            // Exchange withdraws the namespace; the binding stays, because bindings are permanent.
            withdrawNamespaceGrant(tenant)
            worker.run()

            val deferred = publication(tenant.id, catalogKey)
            // Never submitted, so the connection is not blamed for one catalog's stale binding.
            assertThat(deferred.lastError).contains("no longer grants")
            assertThat(deferred.attempts).isZero()
            assertThat(credentials.connection(tenant.id)?.status).isEqualTo(ExchangeConnectionStatus.ACTIVE)
            assertThat(deferred.status.isActive).isTrue()
        }
    }

    @Test
    fun `a namespace can be corrected until a release has reached Exchange, and not after`() {
        val tenant = createTenant("exchange-rebind")
        val catalogKey = CatalogKey.of("rebind-me")
        exchange.namespaces = listOf("public-services", "internal-forms")

        withMediator {
            enroll(tenant)
            SetExchangeDefaultNamespace(tenant.id, "public-services").execute()
            CreateCatalog(tenant.id, catalogKey, "Rebind me").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            assertThat(state(tenant.id, catalogKey).boundNamespace).isEqualTo("public-services")
            assertThat(state(tenant.id, catalogKey).namespaceLocked).isFalse()

            RebindCatalogNamespace(tenant.id, catalogKey, "internal-forms").execute()

            // The binding moves, and the queued release follows it — nothing was submitted anywhere.
            assertThat(state(tenant.id, catalogKey).boundNamespace).isEqualTo("internal-forms")
            assertThat(publication(tenant.id, catalogKey).namespace).isEqualTo("internal-forms")

            // Once Exchange has seen it, the coordinates are fixed.
            worker.run()
            assertThat(state(tenant.id, catalogKey).namespaceLocked).isTrue()
            assertThatThrownBy { RebindCatalogNamespace(tenant.id, catalogKey, "public-services").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_NAMESPACE_LOCKED)
                }
        }
    }

    @Test
    fun `a catalog cannot be moved to a namespace the connection does not grant`() {
        val tenant = createTenant("exchange-rebind-ungranted")
        val catalogKey = CatalogKey.of("rebind-ungranted")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Rebind ungranted").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            assertThatThrownBy { RebindCatalogNamespace(tenant.id, catalogKey, "not-ours").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE)
                }
        }
    }

    private fun enroll(tenant: Tenant) {
        SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
        StartExchangeConnection(tenant.id, CALLBACK).execute()
        CompleteExchangeConnection(
            tenant.id,
            requireNotNull(exchange.latestState.get()),
            "authorization-code",
            FakeExchangeServer.OAUTH_APPLICATION_ID,
            exchange.baseUrl,
        ).execute()
    }

    /**
     * Raw SQL: an organization withdrawing a namespace happens at Exchange, and Suite only learns of
     * it on the next authorization — which is the very thing this test needs to happen *without*.
     */
    private fun withdrawNamespaceGrant(tenant: Tenant) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            "UPDATE exchange_tenant_connections SET namespaces = ARRAY['something-else']::VARCHAR[] WHERE tenant_key = :tenantKey",
        ).bind("tenantKey", tenant.id).execute()
    }

    private fun state(tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query())

    private fun publication(tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: CatalogKey) = state(tenantKey, catalogKey).publications.single()

    companion object {
        private const val CALLBACK = "https://suite.example/oauth/exchange/callback"
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
