package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TenantCommandsTest : BaseIntegrationTest() {
    @Test
    fun `CreateTenant creates tenant with name`() = fixture {
        whenever {
            createTenant("Acme Corp")
        }

        then {
            val tenant = result<Tenant>()
            assertThat(tenant.id).isNotNull()
            assertThat(tenant.name).isEqualTo("Acme Corp")
            assertThat(tenant.createdAt).isNotNull()
        }
    }

    @Test
    fun `DeleteTenant removes tenant by id`() = fixture {
        lateinit var tenantId: UUID

        given {
            tenantId = tenant("To Delete").id
        }

        whenever {
            deleteTenant(tenantId)
        }

        then {
            assertThat(result<Boolean>()).isTrue()
        }
    }

    @Test
    fun `DeleteTenant returns false for non-existent id`() = fixture {
        whenever {
            deleteTenant(UUID.randomUUID())
        }

        then {
            assertThat(result<Boolean>()).isFalse()
        }
    }
}
