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
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.DataModelValidationException
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** A template's data contract is one schema and one set of examples, edited in place. */
class UpdateDataContractTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()
    private lateinit var templateId: TemplateId

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @BeforeEach
    fun createTemplate() {
        val tenant = createTenant("Data contract")
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
        withMediator { CreateDocumentTemplate(id = templateId, name = "contract").execute().withRequiredDataExample() }
    }

    @Test
    fun `a new template has no schema and keeps what an update does not give`() {
        val examples = withMediator { GetDataContract(templateId).query()!! }.dataExamples
        assertThat(withMediator { GetDataContract(templateId).query()!! }.dataModel).isNull()

        withMediator { UpdateDataContract(templateId, dataModel = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")).execute() }

        val contract = withMediator { GetDataContract(templateId).query()!! }
        assertThat(contract.dataModel!!["properties"]["name"]["type"].asString()).isEqualTo("string")
        assertThat(contract.dataExamples).isEqualTo(examples)
    }

    @Test
    fun `the schema can be cleared`() {
        withMediator { UpdateDataContract(templateId, dataModel = schema("""{"type":"object"}""")).execute() }

        withMediator { UpdateDataContract(templateId, clearDataModel = true).execute() }

        assertThat(withMediator { GetDataContract(templateId).query()!! }.dataModel).isNull()
    }

    @Test
    fun `arbitrary JSON is refused as a schema`() {
        assertThatThrownBy {
            withMediator { UpdateDataContract(templateId, dataModel = schema("""{"schemaVersion":5,"resource":{"type":"template"}}""")).execute() }
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.DATA_CONTRACT_SCHEMA_INVALID)
            .hasMessageContaining("must require an object at its root")
    }

    @Test
    fun `a contract without an example is refused`() {
        assertThatThrownBy { withMediator { UpdateDataContract(templateId, dataExamples = emptyList()).execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.DATA_EXAMPLE_REQUIRED)
            .hasMessageContaining("At least one data example")
    }

    @Test
    fun `an example may omit a required field with a default, not one without`() {
        val result = withMediator {
            UpdateDataContract(
                templateId,
                dataModel = schema("""{"type":"object","required":["country"],"properties":{"country":{"type":"string","default":"Netherlands"}}}"""),
            ).execute()
        }
        assertThat(result.warnings).isEmpty()

        assertThatThrownBy {
            withMediator {
                UpdateDataContract(templateId, dataModel = schema("""{"type":"object","required":["country"],"properties":{"country":{"type":"string"}}}""")).execute()
            }
        }.isInstanceOf(DataModelValidationException::class.java)
    }

    @Test
    fun `forced, examples that do not match come back as warnings and are stored`() {
        val examples = listOf(DataExample(id = "e1", name = "Jane", data = objectMapper.createObjectNode(), key = "jane"))

        val result = withMediator {
            UpdateDataContract(
                templateId,
                dataModel = schema("""{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}"""),
                dataExamples = examples,
                forceUpdate = true,
            ).execute()
        }

        // Warnings are keyed by example key, as UpdateDataContractResult documents: a name need not be unique.
        assertThat(result.warnings).containsOnlyKeys("jane")
        // Stored by its key, as the wire form keeps it (ADR 0033): its id reads as its key.
        assertThat(withMediator { GetDataContract(templateId).query()!! }.dataExamples).isEqualTo(examples.map { it.copy(id = "jane") })
    }
}
