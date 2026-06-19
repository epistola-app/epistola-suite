package app.epistola.suite.backups

import app.epistola.hub.client.error.HubInternalException
import app.epistola.hub.client.error.HubUnavailableException
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
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

    @Autowired
    private lateinit var snapshotPort: SnapshotSyncPort

    @Autowired
    private lateinit var compatibilityPort: CompatibilitySyncPort

    private val fakeSnapshots get() = snapshotPort as FakeSnapshotSyncPort
    private val fakeCompatibility get() = compatibilityPort as FakeCompatibilitySyncPort

    @BeforeEach
    fun resetFakes() {
        fakeSnapshots.unreachable = false
        fakeSnapshots.hubErrored = false
        fakeCompatibility.unreachable = false
        fakeCompatibility.hubErrored = false
    }

    private fun enableSupport(tenantKey: TenantKey) = withMediator {
        SaveFeatureToggle(tenantKey, KnownFeatures.SUPPORT_BACKUPS, enabled = true).execute()
        SaveFeatureToggle(tenantKey, KnownFeatures.SUPPORT_COMPATIBILITY_CHECK, enabled = true).execute()
    }

    @Test
    fun `backups page renders and the Support nav shows the items`() {
        val tenant = createTenant("Backups Page")
        enableSupport(tenant.id)

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Backups")
        assertThat(body).contains("Back up now")
        // Backups is a Beta feature: the page header carries the maturity badge.
        assertThat(body).contains("badge badge-beta")
        // Support nav items (Overview + the toggled features) are linked.
        assertThat(body).contains("/tenants/${tenant.id.value}/support")
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

    @Test
    fun `upgrading page renders a not-connected state instead of 500 when the hub is unreachable`() {
        val tenant = createTenant("Upgrading Hub Down")
        enableSupport(tenant.id)
        fakeCompatibility.unreachable = true

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/upgrading", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Not connected to the Epistola hub")
    }

    @Test
    fun `upgrading page shows a hub-error state when the hub answers UNIMPLEMENTED`() {
        val tenant = createTenant("Upgrading Hub Error")
        enableSupport(tenant.id)
        fakeCompatibility.hubErrored = true

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/upgrading", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("couldn't serve compatibility results")
        assertThat(body).doesNotContain("Not connected to the Epistola hub")
    }

    @Test
    fun `back up now reports a fresh upload, then 'no changes' on an unchanged re-run (dedup)`() {
        val tenant = createTenant("Backup Dedup")
        enableSupport(tenant.id)
        val body = HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm())

        val first = restTemplate.postForEntity("/tenants/${tenant.id.value}/backups", body, String::class.java)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.headers.getFirst("HX-Redirect")).contains("saved=backup")
        assertThat(first.headers.getFirst("HX-Redirect")).doesNotContain("unchanged")

        // Same catalogs ⇒ same fingerprint ⇒ dedup, no new snapshot.
        val second = restTemplate.postForEntity("/tenants/${tenant.id.value}/backups", body, String::class.java)
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(second.headers.getFirst("HX-Redirect")).contains("saved=backup-unchanged")
    }

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    @Test
    fun `support overview page renders the hub connection section`() {
        val tenant = createTenant("Support Overview")
        enableSupport(tenant.id)

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/support", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("Support")
        // The support tier is off in tests, so the page reports it isn't connecting to the hub.
        assertThat(body).contains("support tier is not enabled")
    }

    /** Fake snapshot sync adapter returning one snapshot, or failing (unreachable / hub error) when toggled. */
    class FakeSnapshotSyncPort : SnapshotSyncPort {
        @Volatile
        var unreachable = false

        @Volatile
        var hubErrored = false

        override fun isEnabled(): Boolean = true

        override fun isReady(): Boolean = true

        override fun uploadSnapshot(snapshot: TenantSnapshot): SnapshotUploadResult = SnapshotUploadResult("snap-1", deduplicated = false)

        override fun listSnapshots(tenantKey: TenantKey): List<RemoteSnapshot> {
            if (unreachable) throw HubUnavailableException("hub is unavailable")
            if (hubErrored) throw HubInternalException("Hub returned UNIMPLEMENTED: Method not found")
            return listOf(
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
        }

        override fun downloadSnapshot(
            tenantKey: TenantKey,
            snapshotId: String,
        ): ByteArray = ByteArray(0)
    }

    /** Fake compatibility adapter returning one result, or failing (unreachable / hub error) when toggled. */
    class FakeCompatibilitySyncPort : CompatibilitySyncPort {
        @Volatile
        var unreachable = false

        @Volatile
        var hubErrored = false

        override fun isEnabled(): Boolean = true

        override fun isReady(): Boolean = true

        override fun listCompatibilityResults(tenantKey: TenantKey): List<CompatibilityCheckResult> {
            if (unreachable) throw HubUnavailableException("hub is unavailable")
            if (hubErrored) throw HubInternalException("Hub returned UNIMPLEMENTED: Method not found")
            return listOf(
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
}
