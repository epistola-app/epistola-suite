package app.epistola.suite.tenants

import app.epistola.suite.TestcontainersConfiguration
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.CreateTenantHandler
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.commands.DeleteTenantHandler
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class TenantCommandsTest {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var createTenantHandler: CreateTenantHandler

    @Autowired
    private lateinit var deleteTenantHandler: DeleteTenantHandler

    @BeforeEach
    fun setUp() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM document_templates")
            handle.execute("DELETE FROM tenants")
        }
    }

    @Test
    fun `CreateTenant creates tenant with name`() {
        val tenant = createTenantHandler.handle(CreateTenant("Acme Corp"))

        assertThat(tenant.id).isPositive()
        assertThat(tenant.name).isEqualTo("Acme Corp")
        assertThat(tenant.createdAt).isNotNull()
    }

    @Test
    fun `DeleteTenant removes tenant by id`() {
        val tenant = createTenantHandler.handle(CreateTenant("To Delete"))

        val deleted = deleteTenantHandler.handle(DeleteTenant(tenant.id))

        assertThat(deleted).isTrue()
    }

    @Test
    fun `DeleteTenant returns false for non-existent id`() {
        val deleted = deleteTenantHandler.handle(DeleteTenant(99999))

        assertThat(deleted).isFalse()
    }
}
