// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.node.JsonNodeFactory

class GenerationHistoryRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET generation-history returns dashboard page`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Dashboard Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/generation-history",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Generation History")
            assertThat(response.body).contains("Total Generated")
            assertThat(response.body).contains("In Queue")
            assertThat(response.body).contains("Completed")
            assertThat(response.body).contains("Failed")
            assertThat(response.body).contains("Most Used Templates")
            assertThat(response.body).contains("Recent Jobs")
        }
    }

    @Test
    fun `GET generation-history shows empty state when no data`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Empty Dashboard Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/generation-history",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("No template usage data")
            assertThat(response.body).contains("No generation jobs yet")
            // Says where documents come from, and the statuses read as words, not enum names.
            assertThat(response.body).contains("through the REST API").contains("href=\"/api-docs\"")
            assertThat(response.body).contains(">In progress<").doesNotContain(">IN_PROGRESS<")
        }
    }

    @Test
    fun `GET generation-history search returns HTMX fragment`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("HTMX Search Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${tenant.id}/generation-history/search?status=COMPLETED",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // HTMX fragment response should be a table fragment, not a full page
            assertThat(response.body).doesNotContain("Generation History")
        }
    }

    @Test
    fun `GET generation-history links a template to its own catalog`() = fixture {
        lateinit var tenant: Tenant
        var templateKey = TemplateKey.of("placeholder")
        val billing = CatalogKey.of("billing")

        given {
            tenant = tenant("Catalog Links Tenant")
            CreateCatalog(tenantKey = tenant.id, id = billing, name = "Billing").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), testCatalog(TenantId(tenant.id), billing))
            templateKey = CreateDocumentTemplate(id = templateId, name = "Billing Invoice").execute().id
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            val variant = CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()).execute()!!
            UpdateVariantContent(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = "Billing Invoice")).execute()
            // Generation renders billing's latest release.
            mediator.publishAndRelease(variantId)
            GenerateDocument(
                catalog = testCatalog(tenant.id, billing),
                templateId = templateKey,
                variantId = variant.id,
                data = JsonNodeFactory.instance.objectNode(),
                filename = "billing.pdf",
            ).execute()
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/generation-history",
                String::class.java,
            )
        }

        then {
            val body = result<org.springframework.http.ResponseEntity<String>>().body!!
            // By namespace and key, as the requests recorded the catalog (ADR 0034).
            val namespace = app.epistola.suite.testing.TestNamespaces.of(tenant.id).namespace
            val href = "href=\"/tenants/${tenant.id}/catalogs/$namespace/${billing.value}/templates/${templateKey.value}\""
            // Once under "Most Used Templates", once under "Recent Jobs".
            assertThat(Regex(Regex.escape(href)).findAll(body).count()).isEqualTo(2)
        }
    }
}
