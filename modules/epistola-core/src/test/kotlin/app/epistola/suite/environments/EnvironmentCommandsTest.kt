// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeleteEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EnvironmentCommandsTest : IntegrationTestBase() {

    @Test
    fun `DeleteEnvironment removes an unused environment`(): Unit = withMediator {
        val tenant = createTenant("Env Delete Unused")
        val tenantId = TenantId(tenant.id)
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = envId, name = "Staging").execute()

        val deleted = DeleteEnvironment(id = envId).execute()

        assertThat(deleted).isTrue()
        assertThat(GetEnvironment(id = envId).query()).isNull()
    }

    @Test
    fun `DeleteEnvironment returns false when the environment does not exist`(): Unit = withMediator {
        val tenant = createTenant("Env Delete Missing")
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), TenantId(tenant.id))

        val deleted = DeleteEnvironment(id = envId).execute()

        assertThat(deleted).isFalse()
    }

    @Test
    fun `DeleteEnvironment throws EnvironmentInUseException while a version is active in it`(): Unit = withMediator {
        val tenant = createTenant("Env Delete In Use")
        val tenantId = TenantId(tenant.id)

        // Compose the real publish flow to get an activation: template (auto-creates a variant
        // with a draft version), environment, published contract, then publish to the environment.
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
        val variant = ListVariants(templateId = templateId).query().first()
        val variantId = VariantId(variant.id, templateId)
        val draft = ListVersions(variantId = variantId).query().first()
        val versionId = VersionId(draft.id, variantId)

        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        CreateEnvironment(id = envId, name = "Production").execute()

        PublishContractVersion(templateId = templateId).execute()
        PublishToEnvironment(versionId = versionId, environmentId = envId).execute()

        assertThatThrownBy { DeleteEnvironment(id = envId).execute() }
            .isInstanceOf(EnvironmentInUseException::class.java)
            .hasMessageContaining("active template version")

        // The environment survives the rejected delete.
        assertThat(GetEnvironment(id = envId).query()).isNotNull()

        // Once the activation is removed the delete goes through.
        val removed = RemoveActivation(variantId = variantId, environmentId = envId).execute()
        assertThat(removed).isTrue()
        assertThat(DeleteEnvironment(id = envId).execute()).isTrue()
    }

    @Test
    fun `UpdateEnvironment renames the environment`(): Unit = withMediator {
        val tenant = createTenant("Env Rename")
        val tenantId = TenantId(tenant.id)
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val created = CreateEnvironment(id = envId, name = "Staging").execute()

        val updated = UpdateEnvironment(id = envId, name = "Acceptance").execute()

        assertThat(updated).isNotNull
        assertThat(updated!!.id).isEqualTo(created.id)
        assertThat(updated.name).isEqualTo("Acceptance")
        assertThat(GetEnvironment(id = envId).query()!!.name).isEqualTo("Acceptance")
    }

    @Test
    fun `UpdateEnvironment returns null when the environment does not exist`(): Unit = withMediator {
        val tenant = createTenant("Env Rename Missing")
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), TenantId(tenant.id))

        val updated = UpdateEnvironment(id = envId, name = "Whatever").execute()

        assertThat(updated).isNull()
    }
}
