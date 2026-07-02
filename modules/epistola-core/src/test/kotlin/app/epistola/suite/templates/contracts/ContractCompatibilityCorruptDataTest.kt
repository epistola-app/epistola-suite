package app.epistola.suite.templates.contracts

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.contracts.queries.CheckTemplateVersionCompatibility
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A corrupt database value on the contract-compatibility path must fail the check
 * loudly. Historically it was swallowed and reported as "compatible", which could
 * green-light a breaking contract publish.
 */
class ContractCompatibilityCorruptDataTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    @Test
    fun `corrupt referenced_paths fails the compatibility check instead of reporting compatible`() {
        val tenant = createTenant("Corrupt Paths Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val variantId = VariantId(VariantKey.INITIAL, templateId)

        val draft = withMediator {
            CreateDocumentTemplate(id = templateId, name = "corrupt-paths-template").execute()
            GetDraft(variantId).query()!!
        }

        // Raw SQL on purpose: no command can produce a corrupt referenced_paths value —
        // this simulates a bug in a write path / corrupt row.
        jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE template_versions
                SET referenced_paths = '{"not": "an array"}'::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND variant_key = :variantKey AND id = :versionId
                """,
            )
                .bind("tenantKey", templateId.tenantKey)
                .bind("catalogKey", templateId.catalogKey)
                .bind("templateKey", templateId.key)
                .bind("variantKey", variantId.key)
                .bind("versionId", draft.id.value)
                .execute()
        }

        val newSchema = objectMapper.readValue(
            """{"type":"object","properties":{"name":{"type":"string"}}}""",
            ObjectNode::class.java,
        )

        assertThatThrownBy {
            withMediator {
                CheckTemplateVersionCompatibility(
                    versionId = VersionId(draft.id, variantId),
                    newSchema = newSchema,
                ).query()
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("referenced_paths")
    }
}
