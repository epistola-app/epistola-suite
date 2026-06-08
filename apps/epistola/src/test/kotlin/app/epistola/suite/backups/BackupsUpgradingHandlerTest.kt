package app.epistola.suite.backups

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.catalog.snapshot.TenantSnapshot
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.snapshots.RemoteSnapshot
import app.epistola.suite.snapshots.SnapshotSyncPort
import app.epistola.suite.snapshots.SnapshotUploadResult
import app.epistola.suite.upgrading.CompatibilityCheckResult
import app.epistola.suite.upgrading.CompatibilitySyncPort
import app.epistola.suite.upgrading.CompatibilityVerdict
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
import java.time.Instant

@SpringBootTest(
    classes = [EpistolaSuiteApplication::class, BackupsUpgradingHandlerTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
class BackupsUpgradingHandlerTest : BaseIntegrationTest() {
    @TestConfiguration
    class TestConfig {
        @Bean
        @Primary
        fun fakeSnapshotSyncPort(): SnapshotSyncPort = FakeSnapshotSyncPort()

        @Bean
        @Primary
        fun fakeCompatibilitySyncPort(): CompatibilitySyncPort = FakeCompatibilitySyncPort()
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun enableSupport(tenantKey: TenantKey) = withMediator {
        SaveFeatureToggle(tenantKey, KnownFeatures.SUPPORT_BACKUPS, enabled = true).execute()
        SaveFeatureToggle(tenantKey, KnownFeatures.SUPPORT_UPGRADING, enabled = true).execute()
    }

    @Test
    fun `backups page lists snapshots and the Support nav shows both items`() {
        val tenant = createTenant("Backups Page")
        enableSupport(tenant.id)

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Backups")
        assertThat(body).contains("Back up now")
        // The fake returns one snapshot — its size, suite version + catalog count render.
        assertThat(body).contains("3.0 MB")
        assertThat(body).contains("1.4.0")
        assertThat(body).contains("Latest")
        // Both Support items are linked (their toggles are on).
        assertThat(body).contains("/tenants/${tenant.id.value}/backups")
        assertThat(body).contains("/tenants/${tenant.id.value}/upgrading")
    }

    @Test
    fun `upgrading page shows compatibility results grouped by version`() {
        val tenant = createTenant("Upgrading Page")
        enableSupport(tenant.id)

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/upgrading", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Upgrading")
        assertThat(body).contains("2.4.0")
        assertThat(body).contains("WARN")
        assertThat(body).contains("uses a deprecated node")
    }

    /** Fake snapshot sync adapter returning one snapshot. */
    class FakeSnapshotSyncPort : SnapshotSyncPort {
        override fun isEnabled(): Boolean = true

        override fun isReady(): Boolean = true

        override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult = SnapshotUploadResult("snap-1", deduplicated = false)

        override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> = listOf(
            RemoteSnapshot(
                snapshotId = "snap-1",
                snapshotFingerprint = "f".repeat(64),
                sizeBytes = 3L * 1024 * 1024,
                catalogCount = 4,
                suiteVersion = "1.4.0",
                capturedAt = Instant.parse("2026-01-02T02:00:00Z"),
                createdAt = Instant.parse("2026-01-02T02:00:00Z"),
                isLatest = true,
            ),
        )

        override fun downloadSnapshot(
            tenantKey: TenantKey,
            snapshotId: String,
        ): ByteArray = ByteArray(0)
    }

    /** Fake compatibility adapter returning one result. */
    class FakeCompatibilitySyncPort : CompatibilitySyncPort {
        override fun isEnabled(): Boolean = true

        override fun isReady(): Boolean = true

        override fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> = listOf(
            CompatibilityCheckResult(
                tenant = tenantKey.value,
                targetVersion = "2.4.0",
                snapshotId = "snap-1",
                catalogKey = "my-catalog",
                verdict = CompatibilityVerdict.WARN,
                detail = "uses a deprecated node",
                occurredAt = Instant.parse("2026-01-02T02:00:00Z"),
            ),
        )
    }
}
