// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.model.ValidationError
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class ProblemDetailsTest {

    @Test
    fun `problem body uses RFC 9457 fields and top-level extensions`() {
        val body = problemBody(
            request = request("/api/tenants/acme/catalogs/default/code-lists", "q=iso"),
            type = ApiProblemTypes.CATALOG_READ_ONLY,
            detail = "Cannot modify resources in read-only catalog 'system'.",
            extensions = mapOf("catalogId" to "system"),
        )

        assertThat(body).containsEntry("type", "https://epistola.app/errors/catalog-read-only")
        assertThat(body).containsEntry("title", "Catalog Read Only")
        assertThat(body).containsEntry("status", 409)
        assertThat(body).containsEntry("detail", "Cannot modify resources in read-only catalog 'system'.")
        assertThat(body).containsEntry("instance", "/api/tenants/acme/catalogs/default/code-lists?q=iso")
        assertThat(body).containsEntry("catalogId", "system")
        assertThat(body).doesNotContainKey("code")
        assertThat(body).doesNotContainKey("details")
    }

    @Test
    fun `instance omits query string when absent`() {
        val body = problemBody(
            request = request("/api/tenants/acme/templates"),
            type = ApiProblemTypes.TENANT_NOT_FOUND,
            detail = "Tenant not found.",
        )

        assertThat(body["instance"]).isEqualTo("/api/tenants/acme/templates")
    }

    @Test
    fun `validation problems expose errors at top level`() {
        val body = ValidationException(
            code = ValidationCode.PARAMETER_NAME_INVALID,
            field = "id",
            message = "Template ID must be kebab-case",
        ).toValidationProblemBody(request("/api/tenants/acme/catalogs/default/templates"))

        assertThat(body).containsEntry("type", "https://epistola.app/errors/parameter-name-invalid")
        assertThat(body).containsEntry("title", "Parameter Name Invalid")
        assertThat(body).containsEntry("status", 400)
        assertThat(body).doesNotContainKey("details")

        @Suppress("UNCHECKED_CAST")
        val errors = body["errors"] as List<ValidationError>
        val error = errors.single()
        assertThat(error.field).isEqualTo("id")
        assertThat(error.message).isEqualTo("Template ID must be kebab-case")
        assertThat(error.rejectedValue).isNull()
    }

    @Test
    fun `problem registry has unique codes slugs and type urls`() {
        val all = ApiProblemTypes.all

        assertThat(all.map { it.code }).doesNotHaveDuplicates()
        assertThat(all.map { it.slug }).doesNotHaveDuplicates()
        assertThat(all.map { it.type.toString() }).doesNotHaveDuplicates()
        assertThat(all).allSatisfy { problem ->
            assertThat(problem.type.toString()).isEqualTo("$PROBLEM_TYPE_BASE_URL/${problem.slug}")
        }
    }

    private fun request(uri: String, query: String? = null): MockHttpServletRequest = MockHttpServletRequest().apply {
        requestURI = uri
        queryString = query
    }
}
