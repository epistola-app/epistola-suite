package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.commands.DeleteTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TenantCommandsTest : BaseIntegrationTest() {
    @Test
    fun `CreateTenant creates tenant with name`() {
        val tenant = createTenant("Acme Corp")

        assertThat(tenant.id).isPositive()
        assertThat(tenant.name).isEqualTo("Acme Corp")
        assertThat(tenant.createdAt).isNotNull()
    }

    @Test
    fun `DeleteTenant removes tenant by id`() {
        val tenant = createTenant("To Delete")

        val deleted = deleteTenantHandler.handle(DeleteTenant(tenant.id))

        assertThat(deleted).isTrue()
    }

    @Test
    fun `DeleteTenant returns false for non-existent id`() {
        val deleted = deleteTenantHandler.handle(DeleteTenant(99999))

        assertThat(deleted).isFalse()
    }
}
