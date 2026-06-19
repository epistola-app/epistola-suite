package app.epistola.suite.backups

import app.epistola.hub.client.error.HubUnavailableException
import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.tenantbackup.TenantBackupArtifact
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus

/**
 * The Backups page must degrade gracefully — a notice, not a 500 — when listing backups fails because
 * the hub is unreachable (the hub-backed store throws). Regression guard for the decoupled handler.
 */
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class, BackupsHubUnavailableTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
class BackupsHubUnavailableTest : BaseIntegrationTest() {
    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun unavailableBackupStore(): TenantBackupStore = UnavailableBackupStore()
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `backups page renders a not-connected notice instead of 500 when the hub is unreachable`() {
        val tenant = createTenant("Backups Hub Down")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Couldn't reach the Epistola hub")
    }

    /** A [TenantBackupStore] that fails its hub-backed reads, simulating an unreachable hub. */
    class UnavailableBackupStore : TenantBackupStore {
        override fun save(artifact: TenantBackupArtifact): String = throw HubUnavailableException("hub is unavailable")

        override fun list(tenantKey: TenantKey): List<StoredBackup> = throw HubUnavailableException("hub is unavailable")

        override fun load(
            tenantKey: TenantKey,
            backupId: String,
        ): ByteArray = throw HubUnavailableException("hub is unavailable")

        override fun pruneToRetention(
            tenantKey: TenantKey,
            keep: Int,
        ): Int = 0
    }
}
