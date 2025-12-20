package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TenantCommandsTest : BaseIntegrationTest() {
    @Test
    fun `CreateTenant creates tenant with name`() = fixture {
        whenever {
            createTenant("Acme Corp")
        }

        then {
            val tenant = result<Tenant>()
            assertThat(tenant.id).isPositive()
            assertThat(tenant.name).isEqualTo("Acme Corp")
            assertThat(tenant.createdAt).isNotNull()
        }
    }

    @Test
    fun `DeleteTenant removes tenant by id`() = fixture {
        var tenantId: Long = 0

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
            deleteTenant(99999)
        }

        then {
            assertThat(result<Boolean>()).isFalse()
        }
    }
}
