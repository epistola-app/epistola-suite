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
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.SetTenantCatalogPublishingDefault
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * The release-time half of publication: which releases get queued, and that queueing never
 * depends on Exchange being reachable. The worker's half lives in
 * [CatalogPublicationWorkerIntegrationTest].
 */
@TestPropertySource(properties = ["epistola.exchange.enabled=true"])
class CatalogReleasePublicationIntegrationTest : IntegrationTestBase() {

    @Test
    fun `release command succeeds and queues exact archive while connection is missing`() {
        val tenant = createTenant("Queued Publication")
        val catalogKey = CatalogKey.of("public-catalog")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Public catalog").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()

            val release = ReleaseCatalogVersion(
                tenant.id,
                catalogKey,
                "1.0.0",
                publication = ReleasePublication.PUBLISH,
            ).execute()

            assertThat(release.publicationId).isNotNull()
            val publication = publications(tenant.id, catalogKey).single()
            assertThat(publication.version).isEqualTo("1.0.0")
            assertThat(publication.status).isEqualTo(CatalogPublicationStatus.WAITING_SETUP)
            assertThat(publication.archiveRetained).isTrue()
        }
    }

    @Test
    fun `unchanged current release can be queued later through the production command`() {
        val tenant = createTenant("Publish Current")
        val catalogKey = CatalogKey.of("publish-current")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Publish current").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0", publication = ReleasePublication.SKIP).execute()

            PublishCurrentCatalogRelease(tenant.id, catalogKey).execute()

            val publication = publications(tenant.id, catalogKey).single()
            assertThat(publication.version).isEqualTo("1.0.0")
            assertThat(publication.status).isEqualTo(CatalogPublicationStatus.WAITING_SETUP)
            assertThat(publication.archiveRetained).isTrue()

            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) {
                    assertThat(it.code).isEqualTo(ValidationCode.PUBLICATION_ALREADY_QUEUED)
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
            SetCatalogPublicationSettings(tenant.id, catalogKey, CatalogPublicationPolicy.NEVER, null).execute()

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

            assertThat(ReleaseCatalogVersion(tenant.id, catalogKey, "1.0.0").execute().publicationId).isNotNull()

            SetTenantCatalogPublishingDefault(tenant.id, publishByDefault = false).execute()
            assertThat(ReleaseCatalogVersion(tenant.id, catalogKey, "1.1.0").execute().publicationId).isNull()
            assertThat(publications(tenant.id, catalogKey).map { it.version }).containsExactly("1.0.0")
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

    private fun publications(tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: CatalogKey) = requireNotNull(GetCatalogPublicationState(tenantKey, catalogKey).query()).publications
}
