// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Withdrawing a publication an administrator no longer wants.
 *
 * Queueing the wrong release is an ordinary mistake, and the only previous exits were to wait for
 * Exchange, exhaust the retries, or disconnect the tenant.
 */
class CatalogPublicationWithdrawalTest : ExchangeIntegrationTestBase() {

    @Autowired
    private lateinit var worker: CatalogPublicationWorker

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a queued publication can be withdrawn and queued again later`() {
        val tenant = createTenant("withdraw-queued")
        val catalogKey = CatalogKey.of("withdraw-queued")

        withMediator {
            // A namespace can only be chosen from what the connection grants, so this needs enrolling.
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Withdraw queued").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()

            CancelCatalogPublication(tenant.id, publication(tenant.id, catalogKey).id).execute()

            val cancelled = publication(tenant.id, catalogKey)
            assertThat(cancelled.status).isEqualTo(CatalogPublicationStatus.CANCELLED)
            // The attempt stays in the history, and its archive is released.
            assertThat(cancelled.archiveRetained).isFalse()
            assertThat(cancelled.status.isActive).isFalse()

            // The same release can be queued again; the archive is rebuilt from the unchanged working copy.
            PublishCurrentCatalogRelease(tenant.id, catalogKey).execute()
            val requeued = publication(tenant.id, catalogKey)
            assertThat(requeued.status.isActive).isTrue()
            assertThat(requeued.archiveRetained).isTrue()
            assertThat(requeued.idempotencyKey).isNotEqualTo(cancelled.idempotencyKey)
        }
    }

    @Test
    fun `a publication Exchange is already holding cannot be withdrawn`() {
        val tenant = createTenant("withdraw-submitted")
        val catalogKey = CatalogKey.of("withdraw-submitted")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Withdraw submitted").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            worker.run()
            assertThat(publication(tenant.id, catalogKey).status).isEqualTo(CatalogPublicationStatus.SUBMITTED)

            // Dropping it locally would not stop Exchange publishing it, only stop us learning the outcome.
            assertThatThrownBy { CancelCatalogPublication(tenant.id, publication(tenant.id, catalogKey).id).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.PUBLICATION_NOT_CANCELLABLE)
                }
        }
    }

    @Test
    fun `a catalog recreated under the same key keeps the namespace Exchange already published it under`() {
        val tenant = createTenant("recreate-catalog")
        val catalogKey = CatalogKey.of("recreate-me")
        exchange.namespaces = listOf("public-services", "internal-forms")
        // Accepted, not merely taken: only acceptance means Exchange is holding a release under
        // these coordinates, and that is what the binding has to outlive the catalog to record.
        exchange.submitResponse = { FakeExchangeServer.Response(200, exchange.publicationBody(exchange.remotePublicationId, "ACCEPTED")) }

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Recreate me").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.PUBLISH).execute()
            worker.run()
            assertThat(state(tenant.id, catalogKey).namespaceLocked).isTrue()

            // The catalog is deleted locally. Exchange still holds what it published.
            deleteCatalog(tenant, catalogKey)
            CreateCatalog(tenant.id, catalogKey, "Recreate me").execute()

            // The binding outlived the catalog, so the recreated one cannot claim a second namespace.
            val revived = state(tenant.id, catalogKey)
            assertThat(revived.boundNamespace).isEqualTo("public-services")
            assertThat(revived.namespaceLocked).isTrue()
            assertThatThrownBy { SetCatalogPublicationNamespace(tenant.id, catalogKey, "internal-forms").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_NAMESPACE_LOCKED)
                }
        }
    }

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

    /**
     * Raw SQL: catalog deletion is a multi-step purge with its own command surface, and this test is
     * about what survives it, not about the purge itself.
     */
    private fun deleteCatalog(tenant: Tenant, catalogKey: CatalogKey) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("DELETE FROM catalogs WHERE tenant_key = :tenantKey AND id = :catalogKey")
            .bind("tenantKey", tenant.id).bind("catalogKey", catalogKey).execute()
    }

    private fun state(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query())

    private fun publication(tenantKey: TenantKey, catalogKey: CatalogKey) = state(tenantKey, catalogKey).publications.single()
}
