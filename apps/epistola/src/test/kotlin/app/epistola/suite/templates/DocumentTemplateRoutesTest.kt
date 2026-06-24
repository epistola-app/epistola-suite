package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper

class DocumentTemplateRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var listDocumentTemplatesHandler: ListDocumentTemplatesHandler

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    /**
     * Inserts a draft contract version with the given data model and/or examples.
     */
    private fun insertDraftContract(
        tenantKey: String,
        templateKey: String,
        dataModel: String? = null,
        dataExamples: String = "[]",
    ) {
        jdbi.withHandle<Unit, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO contract_versions (id, tenant_key, catalog_key, template_key, data_model, data_examples, status, created_at)
                VALUES (1, :tenantKey, 'default', :templateKey, :dataModel::jsonb, :dataExamples::jsonb, 'draft', NOW())
                ON CONFLICT (tenant_key, catalog_key, template_key) WHERE status = 'draft'
                DO UPDATE SET data_model = :dataModel::jsonb, data_examples = :dataExamples::jsonb
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("templateKey", templateKey)
                .bind("dataModel", dataModel)
                .bind("dataExamples", dataExamples)
                .execute()
        }
    }

    @Test
    fun `GET templates returns list page with template data`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Document Templates")
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).contains("Contract Template")
            assertThat(response.body).contains("Letter Template")
        }
    }

    @Test
    fun `GET templates returns empty table when no templates exist`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Document Templates")
            assertThat(response.body).doesNotContain("Invoice Template")
        }
    }

    @Test
    fun `GET templates with empty catalog filter shows catalog-specific empty state not onboarding`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            // Tenant has a template (in the default catalog), so it is not a
            // first-run tenant; filtering to an empty catalog must not claim it is.
            template(testTenant, "Invoice Template")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?catalog=marketing", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(body).contains("No templates in this catalog")
            assertThat(body).doesNotContain("No templates yet")
            assertThat(body).doesNotContain("Create your first document template")
        }
    }

    @Test
    fun `GET templates search filters by name`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search?q=Invoice",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).doesNotContain("Contract Template")
            assertThat(response.body).doesNotContain("Letter Template")
        }
    }

    @Test
    fun `GET templates search with empty query returns all templates`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).contains("Contract Template")
            assertThat(response.body).contains("Letter Template")
        }
    }

    private fun rowCount(body: String): Int = body.split("data-testid=\"template-row\"").size - 1

    @Test
    fun `GET templates paginates at 10 rows per page`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            repeat(25) { i -> template(testTenant, "Template %02d".format(i)) }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(rowCount(response.body!!)).isEqualTo(10)
            assertThat(response.body).contains("Page 1 of 3")
            assertThat(response.body).contains("data-testid=\"template-pagination\"")
            assertThat(response.body).contains("data-testid=\"pagination-next\"")
            // First page has no Previous link, only the disabled placeholder.
            assertThat(response.body).doesNotContain("data-testid=\"pagination-prev\"")
            assertThat(response.body).contains("data-testid=\"sort-name\"")
        }
    }

    @Test
    fun `GET templates last page returns the remaining rows`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            repeat(25) { i -> template(testTenant, "Template %02d".format(i)) }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?page=3", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(rowCount(response.body!!)).isEqualTo(5)
            assertThat(response.body).contains("Page 3 of 3")
        }
    }

    @Test
    fun `GET templates with an overflowing page does not 500`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            repeat(25) { i -> template(testTenant, "Template %02d".format(i)) }
        }

        whenever {
            // (page - 1) * PAGE_SIZE would overflow Int to a negative OFFSET if
            // the page were not capped; must clamp to the last page, not 500.
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?page=2147483647", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(rowCount(response.body!!)).isEqualTo(5)
            assertThat(response.body).contains("Page 3 of 3")
        }
    }

    @Test
    fun `GET templates clamps an out-of-range page to the last page`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            repeat(25) { i -> template(testTenant, "Template %02d".format(i)) }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?page=99", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Page 99 overshoots; the windowed count recovers the real total and
            // the request is clamped to the last page (3) with its 5 rows.
            assertThat(rowCount(response.body!!)).isEqualTo(5)
            assertThat(response.body).contains("Page 3 of 3")
        }
    }

    @Test
    fun `GET templates search sorts by name ascending`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Banana")
            template(testTenant, "Apple")
            template(testTenant, "Cherry")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search?sort=name&dir=asc",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(body.indexOf("Apple")).isLessThan(body.indexOf("Banana"))
            assertThat(body.indexOf("Banana")).isLessThan(body.indexOf("Cherry"))
            // All 4 sortable columns always carry exactly one chevron (no layout
            // shift); the active column (name, asc) is the only up-chevron.
            assertThat(body.split("ep-sort-icon").size - 1).isEqualTo(4)
            assertThat(body.split("icon-chevron-up").size - 1).isEqualTo(1)
        }
    }

    @Test
    fun `GET templates search sorts by name descending`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Banana")
            template(testTenant, "Apple")
            template(testTenant, "Cherry")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search?sort=name&dir=desc",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(body.indexOf("Cherry")).isLessThan(body.indexOf("Banana"))
            assertThat(body.indexOf("Banana")).isLessThan(body.indexOf("Apple"))
            // All 4 sortable columns always carry exactly one chevron (no layout
            // shift); sorting descending means none point up.
            assertThat(body.split("ep-sort-icon").size - 1).isEqualTo(4)
            assertThat(body).doesNotContain("icon-chevron-up")
        }
    }

    @Test
    fun `GET templates sort headers honor each column's default direction`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Apple")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // A first click on an inactive column uses its TemplateSort.defaultDescending:
            // Variants/Last Modified are naturally descending, Name/Catalog ascending.
            assertThat(body).contains("sort=variants&amp;dir=desc")
            assertThat(body).contains("sort=name&amp;dir=asc")
            assertThat(body).contains("sort=catalog&amp;dir=asc")
        }
    }

    @Test
    fun `GET templates with invalid sort param falls back to updated`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Apple")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?sort=not-a-column", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // An unknown sort resolves to UPDATED descending: only the active
            // column carries a non-none aria-sort, and its header toggles to asc.
            assertThat(body).contains("aria-sort=\"descending\"")
            assertThat(body).contains("sort=updated&amp;dir=asc")
        }
    }

    @Test
    fun `GET templates sorts by variant count descending`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            val many = template(testTenant, "ManyVariants")
            val some = template(testTenant, "SomeVariants")
            template(testTenant, "FewVariants")
            repeat(2) { variant(testTenant, many) }
            variant(testTenant, some)
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?sort=variants&dir=desc", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(body.indexOf("ManyVariants")).isLessThan(body.indexOf("SomeVariants"))
            assertThat(body.indexOf("SomeVariants")).isLessThan(body.indexOf("FewVariants"))
        }
    }

    @Test
    fun `GET templates sorts by catalog ascending`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            val tenantId = TenantId(testTenant.id)
            // Default catalog template created first (older); marketing second.
            // Under sort=updated the marketing row would lead, so an asserted
            // default-before-marketing order proves the catalog sort is applied.
            template(testTenant, "DefaultCatalogTemplate")
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
                CreateDocumentTemplate(
                    id = TemplateId(TemplateKey.of("mkt-tpl"), CatalogId(CatalogKey.of("marketing"), tenantId)),
                    name = "MarketingCatalogTemplate",
                ).execute()
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?sort=catalog&dir=asc", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // catalog asc: "default" precedes "marketing".
            assertThat(body.indexOf("DefaultCatalogTemplate")).isLessThan(body.indexOf("MarketingCatalogTemplate"))
        }
    }

    @Test
    fun `GET templates sorts by last modified descending`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "OldestTemplate")
            template(testTenant, "MiddleTemplate")
            val newest = template(testTenant, "ToBeTouched")
            // Re-touch in a strictly later transaction so its updated_at (NOW())
            // is newest regardless of creation-time tie resolution.
            withMediator {
                UpdateDocumentTemplate(
                    id = TemplateId(newest.id, CatalogId(CatalogKey.of("default"), TenantId(testTenant.id))),
                    name = "RecentlyTouched",
                ).execute()
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?sort=updated&dir=desc", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(body.indexOf("RecentlyTouched")).isLessThan(body.indexOf("OldestTemplate"))
            assertThat(body.indexOf("RecentlyTouched")).isLessThan(body.indexOf("MiddleTemplate"))
        }
    }

    @Test
    fun `GET templates paginates within an active catalog filter`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            val tenantId = TenantId(testTenant.id)
            template(testTenant, "Default Catalog Only")
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
                repeat(12) { i ->
                    CreateDocumentTemplate(
                        id = TemplateId(TemplateKey.of("mkt-%02d".format(i)), CatalogId(CatalogKey.of("marketing"), tenantId)),
                        name = "Marketing %02d".format(i),
                    ).execute()
                }
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates?catalog=marketing&page=2", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            val body = response.body!!
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // 12 marketing templates → 2 pages; page 2 holds the remaining 2, the
            // default-catalog template is excluded, and the filter rides the links.
            assertThat(body).contains("Page 2 of 2")
            assertThat(rowCount(body)).isEqualTo(2)
            assertThat(body).doesNotContain("Default Catalog Only")
            assertThat(body).contains("catalog=marketing")
        }
    }

    @Test
    fun `POST templates creates new template`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "new-template")
            formData.add("name", "New Template")
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("New Template")

            val templates = listDocumentTemplatesHandler.handle(
                ListDocumentTemplates(tenantId = TenantId(testTenant.id), searchTerm = "New Template"),
            )
            assertThat(templates).hasSize(1)
        }
    }

    @Test
    fun `POST templates redirects to detail page on success`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "htmx-template")
            formData.add("name", "HTMX Template")
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // TestRestTemplate follows the 303 redirect to the detail page
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("HTMX Template")

            val templates = listDocumentTemplatesHandler.handle(
                ListDocumentTemplates(tenantId = TenantId(testTenant.id), searchTerm = "HTMX Template"),
            )
            assertThat(templates).hasSize(1)
        }
    }

    @Test
    fun `POST templates with empty name returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "valid-slug")
            formData.add("name", "")
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name is required")
            assertThat(response.body).contains("form-error")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = TenantId(testTenant.id)))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates with blank name returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "valid-slug")
            formData.add("name", "   ")
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name is required")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = TenantId(testTenant.id)))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates with name exceeding 255 characters returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "valid-slug")
            formData.add("name", "a".repeat(256))
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name must be 255 characters or less")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = TenantId(testTenant.id)))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates validation error preserves form value`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "a".repeat(256))
            formData.add("catalog", "default")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The form should contain the submitted value (trimmed but preserved)
            assertThat(response.body).contains("value=\"${"a".repeat(256)}\"")
        }
    }

    @Nested
    inner class ValidateSchemaEndpointTest {

        @Test
        fun `POST validate-schema returns compatible when no examples exist`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":true")
                assertThat(response.body).contains("\"migrations\":[]")
                assertThat(response.body).contains("\"errors\":[]")
            }
        }

        @Test
        fun `POST validate-schema returns compatible when examples match schema`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add data model and example via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"name": {"type": "string"}}}""",
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("name" to "John")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":true")
            }
        }

        @Test
        fun `POST validate-schema returns migrations for type mismatch`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add a data example with a number where string is expected via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("count" to 42)),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Schema expects string but example has number
                val body = """{"schema": {"type": "object", "properties": {"count": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":false")
                assertThat(response.body).contains("\"autoMigratable\":true")
                assertThat(response.body).contains("\"exampleId\":\"1\"")
            }
        }

        @Test
        fun `POST validate-schema returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/$nonExistentTemplateId/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST validate-schema uses provided examples instead of stored ones`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add a stored data example that matches the schema via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "stored",
                                name = "Stored Example",
                                data = objectMapper.valueToTree(mapOf("name" to "John")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Provide different examples in the request that have type mismatch
                val body = """{
                    "schema": {"type": "object", "properties": {"name": {"type": "string"}}},
                    "examples": [{"id": "provided", "name": "Provided Example", "data": {"name": 123}}]
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":false")
                assertThat(response.body).contains("\"exampleId\":\"provided\"")
                assertThat(response.body).doesNotContain("\"exampleId\":\"stored\"")
            }
        }
    }

    @Nested
    inner class DataExampleEndpointsTest {

        @Test
        fun `PATCH data-example updates a single example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add initial data examples via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("name" to "John")),
                            ),
                            DataExample(
                                id = "example-2",
                                name = "Example 2",
                                data = objectMapper.valueToTree(mapOf("name" to "Jane")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated Example", "data": {"name": "Updated John"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"id\":\"example-1\"")
                assertThat(response.body).contains("\"name\":\"Updated Example\"")
                assertThat(response.body).contains("Updated John")

                // Verify the other example is unchanged via contract version
                val contractVersion = mediator.query(GetLatestContractVersion(templateId = TemplateId(template.id, CatalogId.default(TenantId(testTenant.id)))))
                assertThat(contractVersion).isNotNull
                assertThat(contractVersion!!.dataExamples).hasSize(2)
                assertThat(contractVersion.dataExamples.find { it.id == "example-2" }?.name).isEqualTo("Example 2")
            }
        }

        @Test
        fun `PATCH data-example with partial update only updates provided fields`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Original Name",
                                data = objectMapper.valueToTree(mapOf("field" to "original")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Only update name, not data
                val body = """{"name": "New Name"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"name\":\"New Name\"")
                assertThat(response.body).contains("original") // Data unchanged
            }
        }

        @Test
        fun `PATCH data-example returns 404 for non-existent example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/non-existent",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `PATCH data-example returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/$nonExistentTemplateId/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `PATCH data-example validates against schema`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add schema and example via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("count" to 42)),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Invalid: count should be integer but sending string
                val body = """{"data": {"count": "not-a-number"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.body).contains("errors")
            }
        }

        @Test
        fun `PATCH data-example with forceUpdate saves despite validation errors`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("count" to 42)),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {"count": "not-a-number"}, "forceUpdate": true}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("warnings")
                assertThat(response.body).contains("not-a-number")
            }
        }

        @Test
        fun `DELETE data-example removes a single example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("name" to "John")),
                            ),
                            DataExample(
                                id = "example-2",
                                name = "Example 2",
                                data = objectMapper.valueToTree(mapOf("name" to "Jane")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/example-1",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

                // Verify example-1 is gone, example-2 remains
                val contractVersion = mediator.query(GetLatestContractVersion(templateId = TemplateId(template.id, CatalogId.default(TenantId(testTenant.id)))))
                assertThat(contractVersion).isNotNull
                assertThat(contractVersion!!.dataExamples).hasSize(1)
                assertThat(contractVersion.dataExamples[0].id).isEqualTo("example-2")
            }
        }

        @Test
        fun `DELETE data-example returns 404 for non-existent example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataExamples = objectMapper.writeValueAsString(
                        listOf(
                            DataExample(
                                id = "example-1",
                                name = "Example 1",
                                data = objectMapper.valueToTree(mapOf("name" to "John")),
                            ),
                        ),
                    ),
                )
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/data-examples/non-existent",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `DELETE data-example returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/$nonExistentTemplateId/data-examples/example-1",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }
    }

    @Nested
    inner class EditorPageAndAssetsTest {

        @Test
        fun `GET editor page renders and references template editor bundle`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                variantId = variant(testTenant, template, "Default").id
            }

            whenever {
                restTemplate.getForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/editor",
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("id=\"editor-container\"")
                assertThat(response.body).contains("/editor/template-editor-")
            }
        }

        @Test
        fun `GET template editor asset is served in test profile`() = fixture {
            whenever {
                restTemplate.getForEntity("/editor/template-editor.js", String::class.java)
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).isNotBlank
                assertThat(response.body).contains("mountEditor")
            }
        }
    }

    @Nested
    inner class PreviewEndpointTest {

        @Test
        fun `POST preview returns 400 with structured errors when data validation fails`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Create a variant (which also creates a draft)
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that requires 'name' field via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send data without required 'name' field
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_JSON)).isTrue()
                assertThat(response.body).contains("errors")
                assertThat(response.body).contains("name")
            }
        }

        @Test
        fun `POST preview returns 400 with structured errors when data type is wrong`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that expects 'count' to be integer via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send string instead of integer
                val body = """{"data": {"count": "not-a-number"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_JSON)).isTrue()
                assertThat(response.body).contains("errors")
            }
        }

        @Test
        fun `POST preview returns 404 for non-existent variant`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            val nonExistentVariantId = "non-existent-variant"

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$nonExistentVariantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST preview returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"
            val nonExistentVariantId = "non-existent-variant"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/$nonExistentTemplateId/variants/$nonExistentVariantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST preview returns PDF when data is valid`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that requires 'name' field via contract version
                insertDraftContract(
                    tenantKey = testTenant.id.value,
                    templateKey = template.id.value,
                    dataModel = """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send valid data with required 'name' field and a minimal template model
                // The templateModel is required because the draft is created without one
                val body = """{
                    "data": {"name": "John Doe"},
                    "templateModel": {
                        "modelVersion": 1,
                        "root": "root-1",
                        "nodes": {
                            "root-1": {"id": "root-1", "type": "root", "slots": ["slot-1"]}
                        },
                        "slots": {
                            "slot-1": {"id": "slot-1", "nodeId": "root-1", "name": "children", "children": []}
                        },
                        "themeRef": {"type": "inherit"}
                    }
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
            }
        }

        @Test
        fun `POST preview returns PDF when no schema is defined`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // No schema defined, any data should be valid
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Provide a minimal v2 template document since drafts are created without one
                val body = """{
                    "data": {"anything": "goes"},
                    "templateModel": {
                        "modelVersion": 1,
                        "root": "root-1",
                        "nodes": {
                            "root-1": {"id": "root-1", "type": "root", "slots": ["slot-1"]}
                        },
                        "slots": {
                            "slot-1": {"id": "slot-1", "nodeId": "root-1", "name": "children", "children": []}
                        },
                        "themeRef": {"type": "inherit"}
                    }
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
            }
        }
    }

    @Nested
    inner class DraftSaveEndpointTest {

        @Test
        fun `PUT draft returns validation error message for invalid stencil parameter binding`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantKey? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                variantId = variant(testTenant, template, "Default").id
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{
                    "templateModel": {
                        "modelVersion": 1,
                        "root": "root-1",
                        "nodes": {
                            "root-1": {"id": "root-1", "type": "root", "slots": ["slot-1"]},
                            "stencil-1": {
                                "id": "stencil-1",
                                "type": "stencil",
                                "slots": [],
                                "props": {
                                    "stencilId": "letter",
                                    "version": 1,
                                    "parameterSchemaSnapshot": {
                                        "type": "object",
                                        "properties": {"param1": {"type": "string"}},
                                        "required": ["param1"]
                                    },
                                    "parameterBindings": {"param1": "'hello there' &"}
                                }
                            }
                        },
                        "slots": {
                            "slot-1": {"id": "slot-1", "nodeId": "root-1", "name": "children", "children": ["stencil-1"]}
                        },
                        "themeRef": {"type": "inherit"}
                    }
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/default/${template.id}/variants/$variantId/draft",
                    HttpMethod.PUT,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
                // Machine-readable discriminator is the problem `type` URI (the
                // draft-save ValidationProblemDetail), not flattened to a generic
                // validation type nor smuggled as a message prefix.
                assertThat(response.body).contains("\"type\":\"https://epistola.app/errors/node-parameter-binding-syntax-invalid\"")
                assertThat(response.body).contains("parameter binding 'param1' expression is invalid")
                // Field path is carried structurally under errors[].
                assertThat(response.body).contains("content.stencil.props.parameterBindings.param1")
                // The old SCREAMING_CODE: message prefix is gone.
                assertThat(response.body).doesNotContain("SYNTAX_INVALID: parameter")
                assertThat(response.body).doesNotContain("trace")
            }
        }
    }
}
