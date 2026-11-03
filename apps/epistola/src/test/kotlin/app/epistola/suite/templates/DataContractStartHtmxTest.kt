// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import tools.jackson.databind.node.JsonNodeFactory

/**
 * A new template starts with its data. The editor offers the fields the data contract describes,
 * and with none it had only "No fields available"; an empty contract said "No schema defined yet".
 * Now the empty contract says why it comes first and leads on: describe the data, or open the editor.
 */
class DataContractStartHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `an empty contract leads on to describing the data, or to the editor`() = fixture {
        lateinit var tenantKey: String
        lateinit var empty: DocumentTemplate
        lateinit var described: DocumentTemplate
        given {
            val tenant = tenant("Contract Start")
            tenantKey = tenant.id.value
            empty = template(tenant, "Letter")
            described = template(tenant, "Invoice")
            val schema = JsonNodeFactory.instance.objectNode().put("type", "object")
            schema.putObject("properties").putObject("name").put("type", "string")
            val example = DataExample(id = "example-1", name = "Example 1", data = JsonNodeFactory.instance.objectNode().put("name", "Ann"))
            UpdateDataContract(TemplateId(described.id, described.catalogId), dataModel = schema, dataExamples = listOf(example), catalogVersion = null).execute()
        }
        whenever {
            fun page(t: DocumentTemplate, vararg roles: String) = restTemplate.exchange(
                "/tenants/$tenantKey/catalogs/default/templates/${t.id}/data-contract",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { if (roles.isNotEmpty()) set(TEST_TENANT_ROLES_HEADER, roles.joinToString(",")) }),
                String::class.java,
            ).body!!
            listOf(page(empty), page(described), page(empty, "CONTENT_VIEWER"))
        }
        then {
            val (start, withFields, viewer) = result<List<String>>()
            val panel = start.substringAfter("data-testid=\"contract-start\"").substringBefore("</section>")
            assertThat(panel).contains("Start with the data")
                .contains("/templates/${empty.id}/data-contract?edit=true").contains(">Describe the data<")
                .contains("/templates/${empty.id}/variants/initial/editor").contains(">Open editor<")
            assertThat(withFields).`as`("a contract with fields shows them").doesNotContain("data-testid=\"contract-start\"")
            assertThat(viewer).`as`("someone who cannot change it is not invited to")
                .doesNotContain("data-testid=\"contract-start\"").contains("No schema defined yet.")
        }
    }
}
