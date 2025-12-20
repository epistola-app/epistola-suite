package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TenantIsolationTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var createTemplateHandler: CreateDocumentTemplateHandler

    @Autowired
    private lateinit var listTemplatesHandler: ListDocumentTemplatesHandler

    @Autowired
    private lateinit var getTemplateHandler: GetDocumentTemplateHandler

    @Test
    fun `templates are isolated by tenant`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        createTemplateHandler.handle(CreateDocumentTemplate(tenant1.id, "Tenant1 Template"))
        createTemplateHandler.handle(CreateDocumentTemplate(tenant2.id, "Tenant2 Template"))

        val tenant1Templates = listTemplatesHandler.handle(ListDocumentTemplates(tenant1.id))
        assertThat(tenant1Templates).hasSize(1)
        assertThat(tenant1Templates[0].name).isEqualTo("Tenant1 Template")

        val tenant2Templates = listTemplatesHandler.handle(ListDocumentTemplates(tenant2.id))
        assertThat(tenant2Templates).hasSize(1)
        assertThat(tenant2Templates[0].name).isEqualTo("Tenant2 Template")
    }

    @Test
    fun `cannot get template from different tenant`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        val template = createTemplateHandler.handle(
            CreateDocumentTemplate(tenant1.id, "Private Template"),
        )

        val result = getTemplateHandler.handle(
            GetDocumentTemplate(tenantId = tenant2.id, id = template.id),
        )

        assertThat(result).isNull()
    }
}
