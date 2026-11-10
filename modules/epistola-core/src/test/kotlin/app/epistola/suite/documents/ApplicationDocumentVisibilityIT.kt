// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.queries.CountDocuments
import app.epistola.suite.documents.queries.CountGenerationJobs
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetDocumentMetadata
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.queries.FetchGenerationResults
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * A tenant's calling applications see only the documents they requested (ADR 0054, #749): the
 * collect feed, jobs, document metadata, download, cancel and delete. An application marked as
 * seeing every document, and a person, see the whole tenant.
 */
@Timeout(60)
class ApplicationDocumentVisibilityIT : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    @Test
    fun `a restricted application sees only what it requested, everything else is not found`() = scenario {
        given { documentSetup() }
            .whenever { setup -> generated(setup) }
            .then { setup, world ->
                val tenant = setup.tenant.id
                runAs(world.caseSystem) {
                    assertThat(ListDocuments(tenant).query().map { it.id }).containsExactly(world.documentOf(world.caseRequest))
                    assertThat(CountDocuments(tenant).query()).isEqualTo(1)
                    assertThat(ListGenerationJobs(tenant).query().map { it.id }).containsExactly(world.caseRequest.id)
                    assertThat(CountGenerationJobs(tenant).query()).isEqualTo(1)
                    assertThat(fetchAll(tenant).map { it.requestId }).containsExactly(world.caseRequest.id)

                    val registryDocument = world.documentOf(world.registryRequest)
                    assertThat(GetDocumentMetadata(tenant, registryDocument).query()).isNull()
                    assertThat(GetDocument(tenant, registryDocument).query()).isNull()
                    assertThat(GetGenerationJob(tenant, world.registryRequest.id).query()).isNull()
                    assertThat(DeleteDocument(tenant, registryDocument).execute()).`as`("another application's document is not deleted").isFalse
                    assertThat(GetDocumentMetadata(tenant, world.documentOf(world.caseRequest)).query()).isNotNull
                }
                withMediator {
                    assertThat(GetDocumentMetadata(tenant, world.documentOf(world.registryRequest)).query())
                        .`as`("the registry's document survived the case system's delete").isNotNull
                }
            }
    }

    @Test
    fun `a restricted application cannot cancel another application's job`() = scenario {
        given { documentSetup() }
            .whenever { setup ->
                val registry = runAs(principal(setup.tenant.id, "Registry", seesAll = false)) { generate(setup) }
                registry
            }.then { setup, registryRequest ->
                runAs(principal(setup.tenant.id, "Case system", seesAll = false)) {
                    assertThatThrownBy { CancelGenerationJob(setup.tenant.id, registryRequest.id).execute() }
                        .isInstanceOf(GenerationJobNotFoundException::class.java)
                }
            }
    }

    @Test
    fun `an application that sees every document, and a person, see the whole tenant`() = scenario {
        given { documentSetup() }
            .whenever { setup -> generated(setup) }
            .then { setup, world ->
                val tenant = setup.tenant.id
                val everyRequest = listOf(world.caseRequest.id, world.registryRequest.id, world.personRequest.id)
                for (caller in listOf(world.tenantWide, testUser)) {
                    runAs(caller) {
                        assertThat(ListGenerationJobs(tenant).query().map { it.id }).containsExactlyInAnyOrderElementsOf(everyRequest)
                        assertThat(CountDocuments(tenant).query()).isEqualTo(3)
                        assertThat(fetchAll(tenant).map { it.requestId }).containsExactlyInAnyOrderElementsOf(everyRequest)
                        assertThat(GetDocumentMetadata(tenant, world.documentOf(world.caseRequest)).query()).isNotNull
                    }
                }
            }
    }

    @Test
    fun `a restricted application does not see results requested without an application`() = scenario {
        given { documentSetup() }
            .whenever { setup -> withMediator { generate(setup) } }
            .then { setup, personRequest ->
                drainGenerationJobs(setup.tenant.id)
                runAs(principal(setup.tenant.id, "Case system", seesAll = false)) {
                    assertThat(fetchAll(setup.tenant.id)).isEmpty()
                    assertThat(GetGenerationJob(setup.tenant.id, personRequest.id).query()).isNull()
                }
            }
    }

    @Test
    fun `the owner of unattributed documents also sees those requested without an application`() = scenario {
        given { documentSetup() }
            .whenever { setup -> generated(setup) }
            .then { setup, world ->
                val tenant = setup.tenant.id
                val owner = principal(tenant, "Owner", seesAll = false, ownsUnattributed = true)
                runAs(owner) {
                    assertThat(ListGenerationJobs(tenant).query().map { it.id }).containsExactly(world.personRequest.id)
                    assertThat(CountDocuments(tenant).query()).isEqualTo(1)
                    assertThat(fetchAll(tenant).map { it.requestId }).containsExactly(world.personRequest.id)
                    assertThat(GetDocumentMetadata(tenant, world.documentOf(world.personRequest)).query()).isNotNull
                    assertThat(GetDocumentMetadata(tenant, world.documentOf(world.caseRequest)).query())
                        .`as`("another application's document stays hidden from the owner").isNull()
                }
                runAs(world.caseSystem) {
                    assertThat(GetDocumentMetadata(tenant, world.documentOf(world.personRequest)).query())
                        .`as`("a restricted application that does not own them does not see them").isNull()
                }
            }
    }

    private data class World(
        val caseSystem: EpistolaPrincipal,
        val tenantWide: EpistolaPrincipal,
        val caseRequest: DocumentGenerationRequest,
        val registryRequest: DocumentGenerationRequest,
        val personRequest: DocumentGenerationRequest,
        val documents: Map<GenerationRequestKey, DocumentKey>,
    ) {
        fun documentOf(request: DocumentGenerationRequest): DocumentKey = documents.getValue(request.id)
    }

    /** One request each from two restricted applications, a tenant-wide one stays silent, and a person. */
    private fun generated(setup: DocumentSetup): World {
        val tenant = setup.tenant.id
        val caseSystem = principal(tenant, "Case system", seesAll = false)
        val registry = principal(tenant, "Registry", seesAll = false)
        val tenantWide = principal(tenant, "Reporting", seesAll = true)
        val caseRequest = runAs(caseSystem) { generate(setup) }
        val registryRequest = runAs(registry) { generate(setup) }
        val personRequest = withMediator { generate(setup) }
        drainGenerationJobs(tenant)
        val documents = withMediator {
            listOf(caseRequest, registryRequest, personRequest).associate { it.id to GetGenerationJob(tenant, it.id).query()!!.request.documentKey!! }
        }
        return World(caseSystem, tenantWide, caseRequest, registryRequest, personRequest, documents)
    }

    /** A caller authenticated as a new application of [tenant], with every role. */
    private fun principal(tenant: TenantKey, name: String, seesAll: Boolean, ownsUnattributed: Boolean = false): EpistolaPrincipal {
        val application: ApplicationKey = withMediator {
            CreateApplication(tenantKey = tenant, name = name, roles = TenantRole.entries.toSet(), seesAllDocuments = seesAll).execute().id
        }
        val userId = UserKey.of(UUID.randomUUID())
        return EpistolaPrincipal(
            userId = userId,
            externalId = "apikey:$userId",
            email = "apikey-$userId@npa.epistola",
            displayName = name,
            tenantMemberships = mapOf(tenant to TenantRole.entries.toSet()),
            currentTenantId = tenant,
            application = PrincipalApplication(application, seesAllDocuments = seesAll, ownsUnattributedDocuments = ownsUnattributed),
        )
    }

    private fun generate(setup: DocumentSetup): DocumentGenerationRequest = GenerateDocument(
        catalog = testCatalog(setup.tenant.id, CatalogKey.DEFAULT),
        templateId = setup.template.id,
        variantId = setup.variant.id,
        data = objectMapper.createObjectNode().put("name", "Jane"),
        filename = "letter.pdf",
    ).execute()

    private fun fetchAll(tenant: TenantKey) = FetchGenerationResults(
        tenantId = tenant,
        consumerId = "consumer-${UUID.randomUUID()}",
        partitions = (0 until Partition.TOTAL_PARTITIONS).toSet(),
        limit = 100,
    ).query().rows

    private fun ScenarioBuilder.GivenScope.documentSetup(): DocumentSetup {
        val tenant = tenant("Applications")
        val tenantId = TenantId(tenant.id)
        val template = template(tenant.id, "Letter")
        val templateId = TemplateId(template.id, CatalogId.default(tenantId))
        val variant = variant(templateId, "Default")
        val version = released(VariantId(variant.id, templateId), TestTemplateBuilder.buildMinimal(name = "Letter"))
        return DocumentSetup(tenant, template, variant, version)
    }
}
