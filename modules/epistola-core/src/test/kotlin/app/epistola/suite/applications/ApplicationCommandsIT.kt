// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications

import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.apikeys.queries.ListApiKeys
import app.epistola.suite.apikeys.queries.LookupApiKeyByHash
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.commands.DeleteApplication
import app.epistola.suite.applications.commands.SetUnattributedDocumentsOwner
import app.epistola.suite.applications.commands.UpdateApplication
import app.epistola.suite.applications.queries.GetApplication
import app.epistola.suite.applications.queries.ListApplications
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.createApiKey
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class ApplicationCommandsIT : IntegrationTestBase() {

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    @Test
    fun `a key of an application acts with the application's roles and what it sees`() {
        val tenant = createTenant("Apps").id
        withMediator {
            val app = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.DOCUMENT_GENERATOR)).execute()
            val created = CreateApiKey(tenant, "case-2026", applicationId = app.id).execute()

            val looked = LookupApiKeyByHash(apiKeyService.hashKey(created.plaintextKey)).query()!!
            assertThat(looked.roles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
            assertThat(looked.application).isEqualTo(PrincipalApplication(app.id, seesAllDocuments = false))
            assertThat(ListApiKeys(tenant, applicationId = app.id).query().map { it.id }).containsExactly(created.apiKey.id)
            assertThat(GetApplication(tenant, app.id).query()!!.activeKeyCount).isEqualTo(1)
        }
    }

    @Test
    fun `narrowing an application applies to its keys at once`() {
        val tenant = createTenant("Apps").id
        withMediator {
            val created = createApiKey(tenant, "Legacy", roles = setOf(TenantRole.CONTENT_VIEWER))
            val app = created.apiKey.application!!.id
            val hash = apiKeyService.hashKey(created.plaintextKey)

            assertThat(UpdateApplication(tenant, app, "Case system", "Our case system", seesAllDocuments = false).execute()).isTrue
            assertThat(LookupApiKeyByHash(hash).query()!!.application!!.seesAllDocuments).isFalse
            assertThat(GetApplication(tenant, app).query()!!.name).isEqualTo("Case system")
        }
    }

    @Test
    fun `one application of a tenant owns the unattributed documents, and its keys carry it`() {
        val tenant = createTenant("Apps").id
        val other = createTenant("Other").id
        withMediator {
            val case = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            val registry = CreateApplication(tenant, "Registry", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            val elsewhere = CreateApplication(other, "Elsewhere", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            val key = CreateApiKey(tenant, "case", applicationId = case.id).execute()
            val hash = apiKeyService.hashKey(key.plaintextKey)

            assertThat(SetUnattributedDocumentsOwner(tenant, case.id, owns = true).execute()).isTrue
            assertThat(SetUnattributedDocumentsOwner(other, elsewhere.id, owns = true).execute()).isTrue
            assertThat(LookupApiKeyByHash(hash).query()!!.application!!.ownsUnattributedDocuments).isTrue

            assertThat(SetUnattributedDocumentsOwner(tenant, registry.id, owns = true).execute()).isTrue
            assertThat(ListApplications(tenant).query().filter { it.ownsUnattributedDocuments }.map { it.id })
                .`as`("making another the owner takes them from the first").containsExactly(registry.id)
            assertThat(LookupApiKeyByHash(hash).query()!!.application!!.ownsUnattributedDocuments).isFalse
            assertThat(GetApplication(other, elsewhere.id).query()!!.ownsUnattributedDocuments)
                .`as`("another tenant's owner is untouched").isTrue

            assertThat(SetUnattributedDocumentsOwner(tenant, registry.id, owns = false).execute()).isTrue
            assertThat(ListApplications(tenant).query().none { it.ownsUnattributedDocuments }).isTrue
            assertThat(SetUnattributedDocumentsOwner(other, case.id, owns = true).execute())
                .`as`("another tenant's application is not found").isFalse
        }
    }

    @Test
    fun `an application is deleted only once its keys are revoked`() {
        val tenant = createTenant("Apps").id
        withMediator {
            val app = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            val key = CreateApiKey(tenant, "k", applicationId = app.id).execute()

            assertThatThrownBy { DeleteApplication(tenant, app.id).execute() }.isInstanceOf(ValidationException::class.java)

            RevokeApiKey(tenant, key.apiKey.id).execute()
            assertThat(DeleteApplication(tenant, app.id).execute()).isTrue
            assertThat(GetApplication(tenant, app.id).query()).isNull()
            val revoked = ListApiKeys(tenant).query().single()
            assertThat(revoked.application).`as`("the revoked key stays, unlinked").isNull()
            assertThat(revoked.roles).`as`("a key has no roles of its own").isEmpty()
            assertThat(revoked.isUsable()).isFalse
        }
    }

    @Test
    fun `an application cannot be given roles its creator does not hold`() {
        val tenant = createTenant("Apps").id
        val administrator = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "admin",
            email = "admin@example.org",
            displayName = "Admin",
            tenantMemberships = mapOf(tenant to setOf(TenantRole.TENANT_ADMINISTRATOR)),
            currentTenantId = tenant,
        )
        runAs(administrator) {
            assertThatThrownBy { CreateApplication(tenant, "Publisher", roles = setOf(TenantRole.CONTENT_PUBLISHER)).execute() }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("CONTENT_PUBLISHER")
        }
    }

    @Test
    fun `another tenant's application is not found`() {
        val tenant = createTenant("Apps").id
        val other = createTenant("Other").id
        withMediator {
            val app = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.CONTENT_VIEWER)).execute()
            assertThat(GetApplication(other, app.id).query()).isNull()
            assertThat(UpdateApplication(other, app.id, "x", null, seesAllDocuments = true).execute()).isFalse
            assertThat(DeleteApplication(other, app.id).execute()).isFalse
            assertThatThrownBy { CreateApiKey(other, "k", applicationId = app.id).execute() }.isInstanceOf(ValidationException::class.java)
        }
    }
}
