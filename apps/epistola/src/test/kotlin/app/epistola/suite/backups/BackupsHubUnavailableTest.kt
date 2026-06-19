package app.epistola.suite.backups

import app.epistola.hub.client.error.HubException
import app.epistola.hub.client.error.HubUnauthenticatedException
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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

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

    @Autowired
    private lateinit var store: TenantBackupStore

    @org.junit.jupiter.api.BeforeEach
    fun resetFailure() {
        (store as UnavailableBackupStore).failure = HubUnavailableException("hub is unavailable")
    }

    @Test
    fun `backups page renders a not-connected notice instead of 500 when the hub is unreachable`() {
        val tenant = createTenant("Backups Hub Down")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Couldn't reach the Epistola hub")
    }

    @Test
    fun `backups page distinguishes an auth failure from unreachable`() {
        (store as UnavailableBackupStore).failure = HubUnauthenticatedException("Invalid API key")
        val tenant = createTenant("Backups Hub Auth")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/backups", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("rejected this installation's credentials")
        assertThat(response.body!!).doesNotContain("Couldn't reach the Epistola hub")
    }

    @Test
    fun `back up now redirects to a hub-auth notice when the hub rejects credentials`() {
        (store as UnavailableBackupStore).failure = HubUnauthenticatedException("Invalid API key")
        val tenant = createTenant("Backup Hub Auth")
        val body = HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm())

        val response = restTemplate.postForEntity("/tenants/${tenant.id.value}/backups", body, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("HX-Redirect")).contains("error=hub-auth")
    }

    @Test
    fun `back up now redirects to a hub-unavailable notice instead of a generic failure`() {
        val tenant = createTenant("Backup Hub Down")
        val body = HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm())

        val response = restTemplate.postForEntity("/tenants/${tenant.id.value}/backups", body, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("HX-Redirect")).contains("error=hub-unavailable")
    }

    @Test
    fun `restore redirects to a hub-unavailable notice instead of a generic failure`() {
        val tenant = createTenant("Restore Hub Down")
        val body = HttpEntity(LinkedMultiValueMap<String, String>(), htmxForm())

        val response = restTemplate.postForEntity("/tenants/${tenant.id.value}/backups/any-id/restore", body, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("HX-Redirect")).contains("error=hub-unavailable")
    }

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    /** A [TenantBackupStore] whose hub-backed reads throw a configurable [HubException] per test. */
    class UnavailableBackupStore : TenantBackupStore {
        @Volatile
        var failure: HubException = HubUnavailableException("hub is unavailable")

        override fun save(artifact: TenantBackupArtifact): String = throw failure

        override fun list(tenantKey: TenantKey): List<StoredBackup> = throw failure

        override fun load(
            tenantKey: TenantKey,
            backupId: String,
        ): ByteArray = throw failure

        override fun pruneToRetention(
            tenantKey: TenantKey,
            keep: Int,
        ): Int = 0
    }
}
