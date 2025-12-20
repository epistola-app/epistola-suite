package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplateHandler
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TenantIsolationTest : BaseIntegrationTest() {
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
            listTemplates(tenant1) to listTemplates(tenant2)
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
        var templateId: Long = 0

        given {
            val tenant1 = tenant("Tenant 1")
            tenant2 = tenant("Tenant 2")
            templateId = template(tenant1, "Private Template").id
        }

        whenever {
            getTemplateHandler.handle(GetDocumentTemplate(tenantId = tenant2.id, id = templateId))
        }

        then {
            assertThat(result<DocumentTemplate?>()).isNull()
        }
    }
}
