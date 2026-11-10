// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.apikeys

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.apikeys.queries.LookupApiKeyByHash
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.createApiKey
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ApiKeyIntegrationTest : BaseIntegrationTest() {

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
            created = createApiKey(tenant.id, "CI/CD Pipeline")
        }

        then {
            assertThat(created.plaintextKey).startsWith("epk_")
            assertThat(created.apiKey.name).isEqualTo("CI/CD Pipeline")
            assertThat(created.apiKey.enabled).isTrue()
            assertThat(created.apiKey.keyPrefix).startsWith("epk_")
            assertThat(created.apiKey.keyPrefix).endsWith("...")

            // Verify lookup by hash works
            val keyHash = apiKeyService.hashKey(created.plaintextKey)
            val found = LookupApiKeyByHash(keyHash).query()
            assertThat(found).isNotNull
            assertThat(found!!.id).isEqualTo(created.apiKey.id)
            assertThat(found.tenantKey).isEqualTo(created.apiKey.tenantKey)
        }
    }

    @Test
    fun `revoke API key disables it and records audit info`() = fixture {
        lateinit var tenant: Tenant
        lateinit var created: ApiKeyWithSecret

        given {
            tenant = tenant("Revoke Test")
        }

        whenever {
            created = createApiKey(tenant.id, "To Revoke")
            // revokedBy left null here because no users-table row exists for the test
            // principal in this lightweight fixture; the per-user audit is covered by
            // ApiKeyHandlerTest which seeds the user.
            RevokeApiKey(tenantId = tenant.id, id = created.apiKey.id).execute()
        }

        then {
            val keyHash = apiKeyService.hashKey(created.plaintextKey)
            val found = LookupApiKeyByHash(keyHash).query()
            assertThat(found).isNotNull
            assertThat(found!!.enabled).isFalse()
            assertThat(found.revokedAt).isNotNull
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
            createApiKey(tenant.id, "Key 1")
            createApiKey(tenant.id, "Key 2")
            keys = ListApiKeys(tenantId = tenant.id).query()
        }

        then {
            assertThat(keys).hasSize(2)
            assertThat(keys.map { it.name }).containsExactlyInAnyOrder("Key 1", "Key 2")
        }
    }

    @Test
    fun `create API key persists the chosen role scope and it round-trips on lookup`() = fixture {
        lateinit var tenant: Tenant
        lateinit var created: ApiKeyWithSecret

        given {
            tenant = tenant("Scoped Key")
        }

        whenever {
            created = createApiKey(tenant.id, "Generate-only integration", roles = setOf(TenantRole.CONTENT_VIEWER, TenantRole.DOCUMENT_GENERATOR))
        }

        then {
            // The returned key and the persisted-then-reloaded key both carry exactly the chosen scope —
            // not the full role set — so the auth filter grants only viewer+generator for this key.
            assertThat(created.apiKey.roles)
                .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.DOCUMENT_GENERATOR)

            val reloaded = LookupApiKeyByHash(apiKeyService.hashKey(created.plaintextKey)).query()
            assertThat(reloaded!!.roles)
                .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.DOCUMENT_GENERATOR)
            assertThat(reloaded.roles).doesNotContain(TenantRole.TENANT_ADMINISTRATOR, TenantRole.CONTENT_AUTHOR)
        }
    }

    private fun member(
        tenant: TenantKey,
        tenantRoles: Set<TenantRole>,
        globalRoles: Set<TenantRole> = emptySet(),
    ): EpistolaPrincipal {
        val id = UserKey.generate()
        return EpistolaPrincipal(
            userId = id,
            externalId = "member-${id.value}",
            email = "member-${id.value}@example.com",
            displayName = "Member",
            tenantMemberships = mapOf(tenant to tenantRoles),
            globalRoles = globalRoles,
            currentTenantId = tenant,
        )
    }

    @Test
    fun `an administrator cannot create a key with a role they do not hold`() {
        val tenant = createTenant("Separation of duties")
        val administrator = member(tenant.id, setOf(TenantRole.TENANT_ADMINISTRATOR))

        assertThatThrownBy {
            runAs(administrator) {
                createApiKey(tenant.id, "Publisher key", roles = setOf(TenantRole.CONTENT_PUBLISHER))
            }
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("CONTENT_PUBLISHER")

        assertThat(withMediator { ListApiKeys(tenantId = tenant.id).query() }).isEmpty()
    }

    @Test
    fun `an administrator who also holds a role may grant it`() {
        val tenant = createTenant("Administrator and publisher")
        val administrator = member(tenant.id, setOf(TenantRole.TENANT_ADMINISTRATOR, TenantRole.CONTENT_PUBLISHER))

        val created = runAs(administrator) {
            createApiKey(tenant.id, "Publisher key", roles = setOf(TenantRole.CONTENT_PUBLISHER))
        }

        assertThat(created.apiKey.roles).containsExactly(TenantRole.CONTENT_PUBLISHER)
    }

    @Test
    fun `a global role counts towards the roles an administrator may grant in a tenant`() {
        val tenant = createTenant("Global publisher")
        val administrator = member(
            tenant.id,
            tenantRoles = setOf(TenantRole.TENANT_ADMINISTRATOR),
            globalRoles = setOf(TenantRole.CONTENT_PUBLISHER),
        )

        val created = runAs(administrator) {
            createApiKey(tenant.id, "Publisher key", roles = setOf(TenantRole.CONTENT_PUBLISHER))
        }

        assertThat(created.apiKey.roles).containsExactly(TenantRole.CONTENT_PUBLISHER)
    }

    @Test
    fun `API key domain model expiry and usability checks`() {
        val now = EpistolaClock.instant()
        val activeKey = ApiKey(
            id = app.epistola.suite.common.ids.ApiKeyKey.generate(),
            tenantKey = app.epistola.suite.common.ids.TenantKey.of("test-tenant"),
            name = "Active",
            keyPrefix = "epk_test1234...",
            enabled = true,
            createdAt = now,
            lastUsedAt = null,
            expiresAt = null,
            createdBy = null,
            application = PrincipalApplication(ApplicationKey.generate(), seesAllDocuments = true),
        )
        assertThat(activeKey.isExpired()).isFalse()
        assertThat(activeKey.isUsable()).isTrue()

        val expiredKey = activeKey.copy(expiresAt = now.minusSeconds(3600))
        assertThat(expiredKey.isExpired()).isTrue()
        assertThat(expiredKey.isUsable()).isFalse()

        val disabledKey = activeKey.copy(enabled = false)
        assertThat(disabledKey.isExpired()).isFalse()
        assertThat(disabledKey.isUsable()).isFalse()

        val futureExpiryKey = activeKey.copy(expiresAt = now.plusSeconds(86400))
        assertThat(futureExpiryKey.isExpired()).isFalse()
        assertThat(futureExpiryKey.isUsable()).isTrue()

        val withoutApplication = activeKey.copy(application = null)
        assertThat(withoutApplication.isUsable()).`as`("a key without an application has no roles to act with").isFalse()
    }
}
