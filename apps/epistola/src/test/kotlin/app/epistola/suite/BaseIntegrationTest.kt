package app.epistola.suite

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Base class for app-level integration tests.
 *
 * Extends [IntegrationTestBase] with:
 * - Full random-port application context ([EpistolaSuiteApplication]) instead of [TestApplication]
 * - Shared [TestRestTemplate] auto-configuration for HTTP-level app tests
 * - [TestSecurityContextConfiguration] for HTTP request principal binding
 * - Per-class database cleanup via namespaced tenant deletion
 */
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["epistola.demo.enabled=false"],
)
@AutoConfigureTestRestTemplate
@Import(app.epistola.suite.config.TestSecurityContextConfiguration::class)
abstract class BaseIntegrationTest : IntegrationTestBase() {

    private val classNamespace = this::class.simpleName!!.lowercase().take(20)
    private var tenantCounter = 0

    @BeforeEach
    fun resetDatabaseState(): Unit = withMediator {
        tenantCounter = 0
        ListTenants(idPrefix = classNamespace).query().forEach { tenant ->
            DeleteTenant(tenant.id).execute()
        }
    }
}
