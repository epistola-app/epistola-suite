// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.commands.SetCatalogPublicationSettings
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.SetTenantCatalogPublishingDefault
import app.epistola.suite.testing.FakeExchangeServer
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The release-time half of publication: which releases get queued, and that queueing never
 * depends on Exchange being reachable. The worker's half lives in
 * [CatalogPublicationWorkerIntegrationTest].
 */
class CatalogReleasePublicationIntegrationTest : ExchangeIntegrationTestBase() {

    @Test
    fun `a release with nowhere to publish still succeeds and queues nothing`() {
        val tenant = createTenant("Queued Publication")
        val catalogKey = CatalogKey.of("public-catalog")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Public catalog").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()

            // No namespace has been chosen, so there is nowhere for this release to go.
            val release = ReleaseCatalogVersion(
                tenant.id,
                catalogKey,
                "1.0.0",
                publication = ReleasePublication.PUBLISH,
            ).execute()

            // The local release is unaffected — that is the point of the outbox being separate.
            assertThat(release.version).isEqualTo("1.0.0")
            // But nothing is queued: work that cannot move is not created rather than left waiting.
            assertThat(release.publicationId).isNull()
            assertThat(publications(tenant.id, catalogKey)).isEmpty()
        }
    }

    @Test
    fun `publishing an existing release needs a namespace first`() {
        val tenant = createTenant("Publish Current")
        val catalogKey = CatalogKey.of("publish-current")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Publish current").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.SKIP).execute()

            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE)
                }
            assertThat(publications(tenant.id, catalogKey)).isEmpty()
        }
    }

    @Test
    fun `a release left unpublished while the catalog moves on says so instead of going quiet`() {
        val tenant = createTenant("Drifted Release")
        val catalogKey = CatalogKey.of("drifted-release")

        withMediator {
            enroll(tenant)
            CreateCatalog(tenant.id, catalogKey, "Drifted release").execute()
            SetCatalogPublicationNamespace(tenant.id, catalogKey, "public-services").execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.SKIP).execute()

            // Unchanged, so it can still be sent.
            assertThat(state(tenant.id, catalogKey).canPublishCurrentRelease).isTrue()
            assertThat(state(tenant.id, catalogKey).unpublishableRelease).isNull()

            // Any edit moves the working copy away from the released bytes, which are not retained
            // for a release that was never queued.
            UpdateCatalogMetadata(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                name = "Drifted release",
                description = "Changed after releasing",
                attributes = emptyList(),
            ).execute()

            val drifted = state(tenant.id, catalogKey)
            assertThat(drifted.canPublishCurrentRelease).isFalse()
            // The action disappearing is not an explanation; the version is named so an author can act.
            assertThat(drifted.unpublishableRelease).isEqualTo("1.0.0")

            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.PUBLICATION_WORKING_COPY_DRIFTED)
                }
        }
    }

    @Test
    fun `NEVER policy blocks both the automatic and the explicit path`() {
        val tenant = createTenant("Never Publishes")
        val catalogKey = CatalogKey.of("never-publishes")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Never publishes").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            SetTenantCatalogPublishingDefault(tenant.id, publishByDefault = true).execute()
            SetCatalogPublicationSettings(tenant.id, catalogKey, CatalogPublicationPolicy.NEVER).execute()

            // Even an explicit release-time opt-in cannot override a hard policy.
            val release = ReleaseCatalogVersion(
                tenant.id,
                catalogKey,
                "1.0.0",
                publication = ReleasePublication.PUBLISH,
            ).execute()

            assertThat(release.publicationId).isNull()
            assertThat(publications(tenant.id, catalogKey)).isEmpty()
            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.PUBLICATION_FORBIDDEN_BY_POLICY)
                }
        }
    }

    @Test
    fun `tenant default drives an inheriting catalog when the release expresses no choice`() {
        val tenant = createTenant("Inherits Default")
        val catalogKey = CatalogKey.of("inherits-default")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Inherits default").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            SetTenantCatalogPublishingDefault(tenant.id, publishByDefault = true).execute()

            // Nothing is bound, so even an inheriting catalog with the tenant default on queues nothing.
            assertThat(ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0").execute().publicationId).isNull()

            SetTenantCatalogPublishingDefault(tenant.id, publishByDefault = false).execute()
            assertThat(ReleaseCatalogVersion(tenant.id, catalogKey, "1.1.0").execute().publicationId).isNull()
            assertThat(publications(tenant.id, catalogKey)).isEmpty()
        }
    }

    @Test
    fun `the tenant feature gates queueing even while the deployment gate is on`() {
        val tenant = createTenant("Feature Off")
        val catalogKey = CatalogKey.of("feature-off")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Feature off").execute()
            SetTenantCatalogPublishingDefault(tenant.id, publishByDefault = true).execute()

            assertThat(ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0").execute().publicationId).isNull()
            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.PUBLICATION_UNAVAILABLE)
                }
        }
    }

    private fun state(tenantKey: TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query())

    private fun publications(tenantKey: TenantKey, catalogKey: CatalogKey) = state(tenantKey, catalogKey).publications

    /** Choosing a namespace needs a connection, so the drift case has to be a fully enrolled tenant. */
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
}
