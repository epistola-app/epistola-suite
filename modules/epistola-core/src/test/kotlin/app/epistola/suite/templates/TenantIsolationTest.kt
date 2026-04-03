package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TenantIsolationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var getTemplateHandler: GetDocumentTemplateHandler

    @Test
    fun `templates are isolated by tenant`() = fixture {
        lateinit var tenant1: Tenant
        lateinit var tenant2: Tenant

        given {
            tenant1 = tenant("Tenant 1")
            tenant2 = tenant("Tenant 2")
            template(tenant1, "Tenant1 Template")
            template(tenant2, "Tenant2 Template")
        }

        whenever {
            listTemplates(TenantId(tenant1.id)) to listTemplates(TenantId(tenant2.id))
        }

        then {
            val (tenant1Templates, tenant2Templates) = result<Pair<List<DocumentTemplate>, List<DocumentTemplate>>>()

            assertThat(tenant1Templates).hasSize(1)
            assertThat(tenant1Templates[0].name).isEqualTo("Tenant1 Template")

            assertThat(tenant2Templates).hasSize(1)
            assertThat(tenant2Templates[0].name).isEqualTo("Tenant2 Template")
        }
    }

    @Test
    fun `cannot get template from different tenant`() = fixture {
        lateinit var tenant2: Tenant
        var templateKey: TemplateKey? = null

        given {
            val tenant1 = tenant("Tenant 1")
            tenant2 = tenant("Tenant 2")
            templateKey = template(tenant1, "Private Template").id
        }

        whenever {
            getTemplateHandler.handle(GetDocumentTemplate(id = TemplateId(templateKey!!, TenantId(tenant2.id))))
        }

        then {
            assertThat(result<DocumentTemplate?>()).isNull()
        }
    }

    @Test
    fun `two tenants can use the same template slug`() {
        val tenant1 = createTenant("Tenant A")
        val tenant2 = createTenant("Tenant B")
        val sharedSlug = TemplateKey.of("invoice")

        withMediator {
            val tenantId1 = TenantId(tenant1.id)
            val tenantId2 = TenantId(tenant2.id)

            val template1 = CreateDocumentTemplate(id = TemplateId(sharedSlug, tenantId1), name = "Invoice A").execute()
            val template2 = CreateDocumentTemplate(id = TemplateId(sharedSlug, tenantId2), name = "Invoice B").execute()

            assertThat(template1.id).isEqualTo(sharedSlug)
            assertThat(template2.id).isEqualTo(sharedSlug)

            // Each tenant sees only their own template
            val tenant1Templates = ListDocumentTemplates(tenantId1).query()
            val tenant2Templates = ListDocumentTemplates(tenantId2).query()

            assertThat(tenant1Templates).hasSize(1)
            assertThat(tenant1Templates[0].name).isEqualTo("Invoice A")

            assertThat(tenant2Templates).hasSize(1)
            assertThat(tenant2Templates[0].name).isEqualTo("Invoice B")
        }
    }
}
