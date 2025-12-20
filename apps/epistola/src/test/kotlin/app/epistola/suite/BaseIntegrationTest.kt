package app.epistola.suite

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.CreateTenantHandler
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.commands.DeleteTenantHandler
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.tenants.queries.ListTenantsHandler
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
abstract class BaseIntegrationTest {
    @Autowired
    protected lateinit var listTenantsHandler: ListTenantsHandler

    @Autowired
    protected lateinit var createTenantHandler: CreateTenantHandler

    @Autowired
    protected lateinit var deleteTenantHandler: DeleteTenantHandler

    private val createdTenants = mutableListOf<Long>()

    protected fun createTenant(name: String): Tenant {
        val tenant = createTenantHandler.handle(CreateTenant(name))
        createdTenants.add(tenant.id)
        return tenant
    }

    protected fun deleteAllTenants() {
        listTenantsHandler.handle(ListTenants()).forEach { tenant ->
            deleteTenantHandler.handle(DeleteTenant(tenant.id))
        }
        createdTenants.clear()
    }

    @AfterEach
    fun cleanUpCreatedTenants() {
        createdTenants.forEach { tenantId ->
            deleteTenantHandler.handle(DeleteTenant(tenantId))
        }
        createdTenants.clear()
    }
}
