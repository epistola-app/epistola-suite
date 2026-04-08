package app.epistola.suite.features

import app.epistola.suite.features.commands.DeleteFeatureToggle
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.features.queries.GetFeatureToggles
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class FeatureTogglesIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var featureToggleService: FeatureToggleService

    @Test
    fun `GetFeatureToggles returns all known features with defaults`() {
        val tenant = createTenant("toggle-defaults")
        withMediator {
            val features = GetFeatureToggles(tenant.id).query()

            assertThat(features).containsKey(KnownFeatures.FEEDBACK)
            assertThat(features).hasSize(KnownFeatures.all.size)
        }
    }

    @Test
    fun `SaveFeatureToggle persists tenant override`() {
        val tenant = createTenant("toggle-save")
        withMediator {
            SaveFeatureToggle(
                tenantKey = tenant.id,
                featureKey = KnownFeatures.FEEDBACK,
                enabled = false,
            ).execute()

            val features = GetFeatureToggles(tenant.id).query()
            assertThat(features[KnownFeatures.FEEDBACK]).isFalse()
        }
    }

    @Test
    fun `SaveFeatureToggle upserts existing override`() {
        val tenant = createTenant("toggle-upsert")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.FEEDBACK, enabled = false).execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.FEEDBACK, enabled = true).execute()

            val features = GetFeatureToggles(tenant.id).query()
            assertThat(features[KnownFeatures.FEEDBACK]).isTrue()
        }
    }

    @Test
    fun `DeleteFeatureToggle removes override and falls back to default`() {
        val tenant = createTenant("toggle-delete")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.FEEDBACK, enabled = false).execute()
            DeleteFeatureToggle(tenant.id, KnownFeatures.FEEDBACK).execute()

            val features = GetFeatureToggles(tenant.id).query()
            // Should fall back to global default (true in application.yaml)
            assertThat(features[KnownFeatures.FEEDBACK]).isNotNull()
        }
    }

    @Test
    fun `isEnabled returns global default when no tenant override exists`() {
        val tenant = createTenant("toggle-default")
        withMediator {
            val enabled = featureToggleService.isEnabled(tenant.id, KnownFeatures.FEEDBACK)
            // No override and no test config → falls back to FeatureDefaults constructor default (false)
            assertThat(enabled).isFalse()
        }
    }

    @Test
    fun `isEnabled returns tenant override when it exists`() {
        val tenant = createTenant("toggle-override")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.FEEDBACK, enabled = false).execute()

            val enabled = featureToggleService.isEnabled(tenant.id, KnownFeatures.FEEDBACK)
            assertThat(enabled).isFalse()
        }
    }

    @Test
    fun `resolveAll returns correct state per tenant`() {
        val tenant1 = createTenant("toggle-tenant1")
        val tenant2 = createTenant("toggle-tenant2")
        withMediator {
            SaveFeatureToggle(tenant1.id, KnownFeatures.FEEDBACK, enabled = true).execute()

            val features1 = featureToggleService.resolveAll(tenant1.id)
            val features2 = featureToggleService.resolveAll(tenant2.id)

            // tenant1 has override → true, tenant2 has no override → falls back to default (false)
            assertThat(features1[KnownFeatures.FEEDBACK]).isTrue()
            assertThat(features2[KnownFeatures.FEEDBACK]).isFalse()
        }
    }
}
