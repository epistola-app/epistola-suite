package app.epistola.suite

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.TestFixture
import app.epistola.suite.testing.TestFixtureFactory
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
abstract class BaseIntegrationTest {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    private val createdTenants = mutableListOf<Long>()

    protected fun <T> fixture(block: TestFixture.() -> T): T = testFixtureFactory.fixture(block)

    protected fun createTenant(name: String): Tenant {
        val tenant = mediator.send(CreateTenant(name))
        createdTenants.add(tenant.id)
        return tenant
    }

    protected fun deleteAllTenants() {
        mediator.query(ListTenants()).forEach { tenant ->
            mediator.send(DeleteTenant(tenant.id))
        }
        createdTenants.clear()
    }

    @AfterEach
    fun cleanUpCreatedTenants() {
        createdTenants.forEach { tenantId ->
            mediator.send(DeleteTenant(tenantId))
        }
        createdTenants.clear()
    }
}
