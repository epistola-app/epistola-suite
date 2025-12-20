package app.epistola.suite.templates

import app.epistola.suite.TestcontainersConfiguration
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.CreateTenantHandler
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class TenantIsolationTest {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var createTenantHandler: CreateTenantHandler

    @Autowired
    private lateinit var createTemplateHandler: CreateDocumentTemplateHandler

    @Autowired
    private lateinit var listTemplatesHandler: ListDocumentTemplatesHandler

    @Autowired
    private lateinit var getTemplateHandler: GetDocumentTemplateHandler

    @BeforeEach
    fun setUp() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM document_templates")
            handle.execute("DELETE FROM tenants")
        }
    }

    @Test
    fun `templates are isolated by tenant`() {
        // Create two tenants
        val tenant1 = createTenantHandler.handle(CreateTenant("Tenant 1"))
        val tenant2 = createTenantHandler.handle(CreateTenant("Tenant 2"))

        // Create templates for each tenant
        createTemplateHandler.handle(CreateDocumentTemplate(tenant1.id, "Tenant1 Template"))
        createTemplateHandler.handle(CreateDocumentTemplate(tenant2.id, "Tenant2 Template"))

        // List templates for tenant 1 - should only see their own
        val tenant1Templates = listTemplatesHandler.handle(ListDocumentTemplates(tenant1.id))
        assertThat(tenant1Templates).hasSize(1)
        assertThat(tenant1Templates[0].name).isEqualTo("Tenant1 Template")

        // List templates for tenant 2 - should only see their own
        val tenant2Templates = listTemplatesHandler.handle(ListDocumentTemplates(tenant2.id))
        assertThat(tenant2Templates).hasSize(1)
        assertThat(tenant2Templates[0].name).isEqualTo("Tenant2 Template")
    }

    @Test
    fun `cannot get template from different tenant`() {
        val tenant1 = createTenantHandler.handle(CreateTenant("Tenant 1"))
        val tenant2 = createTenantHandler.handle(CreateTenant("Tenant 2"))

        val template = createTemplateHandler.handle(
            CreateDocumentTemplate(tenant1.id, "Private Template"),
        )

        // Tenant 2 should not be able to access Tenant 1's template
        val result = getTemplateHandler.handle(
            GetDocumentTemplate(tenantId = tenant2.id, id = template.id),
        )

        assertThat(result).isNull()
    }
}
