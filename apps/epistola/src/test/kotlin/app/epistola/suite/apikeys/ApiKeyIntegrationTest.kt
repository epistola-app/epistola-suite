package app.epistola.suite.apikeys

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class ApiKeyIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var apiKeyRepository: ApiKeyRepository

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    @Test
    fun `create API key stores hash and returns plaintext once`() = fixture {
        lateinit var tenant: Tenant
        lateinit var created: ApiKeyWithSecret

        given {
            tenant = tenant("Key Holder")
        }

        whenever {
            created = CreateApiKey(tenantId = tenant.id, name = "CI/CD Pipeline").execute()
        }

        then {
            assertThat(created.plaintextKey).startsWith("epk_")
            assertThat(created.apiKey.name).isEqualTo("CI/CD Pipeline")
            assertThat(created.apiKey.enabled).isTrue()
            assertThat(created.apiKey.keyPrefix).startsWith("epk_")
            assertThat(created.apiKey.keyPrefix).endsWith("...")

            // Verify lookup by hash works
            val keyHash = apiKeyService.hashKey(created.plaintextKey)
            val found = apiKeyRepository.findByKeyHash(keyHash)
            assertThat(found).isNotNull()
            assertThat(found!!.id).isEqualTo(created.apiKey.id)
            assertThat(found.tenantId).isEqualTo(created.apiKey.tenantId)
        }
    }

    @Test
    fun `revoke API key disables it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var created: ApiKeyWithSecret

        given {
            tenant = tenant("Revoke Test")
        }

        whenever {
            created = CreateApiKey(tenantId = tenant.id, name = "To Revoke").execute()
            RevokeApiKey(id = created.apiKey.id).execute()
        }

        then {
            val keyHash = apiKeyService.hashKey(created.plaintextKey)
            val found = apiKeyRepository.findByKeyHash(keyHash)
            assertThat(found).isNotNull()
            assertThat(found!!.enabled).isFalse()
        }
    }

    @Test
    fun `list API keys returns all keys for tenant`() = fixture {
        lateinit var tenant: Tenant
        lateinit var keys: List<ApiKey>

        given {
            tenant = tenant("List Test")
        }

        whenever {
            CreateApiKey(tenantId = tenant.id, name = "Key 1").execute()
            CreateApiKey(tenantId = tenant.id, name = "Key 2").execute()
            keys = ListApiKeys(tenantId = tenant.id).query()
        }

        then {
            assertThat(keys).hasSize(2)
            assertThat(keys.map { it.name }).containsExactlyInAnyOrder("Key 1", "Key 2")
        }
    }

    @Test
    fun `API key domain model expiry and usability checks`() {
        val activeKey = ApiKey(
            id = app.epistola.suite.common.ids.ApiKeyId.generate(),
            tenantId = app.epistola.suite.common.ids.TenantId.of("test-tenant"),
            name = "Active",
            keyPrefix = "epk_test1234...",
            enabled = true,
            createdAt = Instant.now(),
            lastUsedAt = null,
            expiresAt = null,
            createdBy = null,
        )
        assertThat(activeKey.isExpired()).isFalse()
        assertThat(activeKey.isUsable()).isTrue()

        val expiredKey = activeKey.copy(expiresAt = Instant.now().minusSeconds(3600))
        assertThat(expiredKey.isExpired()).isTrue()
        assertThat(expiredKey.isUsable()).isFalse()

        val disabledKey = activeKey.copy(enabled = false)
        assertThat(disabledKey.isExpired()).isFalse()
        assertThat(disabledKey.isUsable()).isFalse()

        val futureExpiryKey = activeKey.copy(expiresAt = Instant.now().plusSeconds(86400))
        assertThat(futureExpiryKey.isExpired()).isFalse()
        assertThat(futureExpiryKey.isUsable()).isTrue()
    }
}
