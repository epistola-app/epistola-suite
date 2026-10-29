// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** A template can be created with its data contract, held to what an update of the contract is held to. */
class CreateTemplateWithContractTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private lateinit var templateId: TemplateId

    private fun json(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private val nameRequired = """{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""

    @BeforeEach
    fun tenant() {
        val tenant = createTenant("Create with contract")
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
    }

    @Test
    fun `the template is created with its schema and examples`() {
        val example = DataExample(id = "e1", name = "Ada", data = json("""{"name":"Ada"}"""), key = "ada")

        withMediator { CreateDocumentTemplate(id = templateId, name = "Letter", dataModel = json(nameRequired), dataExamples = listOf(example)).execute() }

        val contract = withMediator { GetDataContract(templateId).query()!! }
        assertThat(contract.dataModel).isEqualTo(json(nameRequired))
        assertThat(contract.dataExamples).isEqualTo(listOf(example.copy(id = "ada")))
    }

    @Test
    fun `examples the schema refuses refuse the template, and nothing is created`() {
        val example = DataExample(id = "e1", name = "Nameless", data = json("{}"), key = "nameless")

        assertThatThrownBy {
            withMediator { CreateDocumentTemplate(id = templateId, name = "Letter", dataModel = json(nameRequired), dataExamples = listOf(example)).execute() }
        }.isInstanceOf(DataModelValidationException::class.java)

        assertThat(withMediator { GetDataContract(templateId).query() }).isNull()
    }

    @Test
    fun `a schema without an example is refused, as an update of one is`() {
        assertThatThrownBy {
            withMediator { CreateDocumentTemplate(id = templateId, name = "Letter", dataModel = json(nameRequired)).execute() }
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.DATA_EXAMPLE_REQUIRED)

        assertThat(withMediator { GetDataContract(templateId).query() }).isNull()
    }

    @Test
    fun `arbitrary JSON is refused as a schema`() {
        assertThatThrownBy {
            withMediator {
                CreateDocumentTemplate(
                    id = templateId,
                    name = "Letter",
                    dataModel = json("""{"schemaVersion":5}"""),
                    dataExamples = listOf(DataExample(id = "e1", name = "Any", data = json("{}"), key = "any")),
                ).execute()
            }
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.DATA_CONTRACT_SCHEMA_INVALID)
    }

    @Test
    fun `without a contract the template has no schema and no examples`() {
        withMediator { CreateDocumentTemplate(id = templateId, name = "Letter").execute() }

        val contract = withMediator { GetDataContract(templateId).query()!! }
        assertThat(contract.dataModel).isNull()
        assertThat(contract.dataExamples).isEmpty()
    }
}
