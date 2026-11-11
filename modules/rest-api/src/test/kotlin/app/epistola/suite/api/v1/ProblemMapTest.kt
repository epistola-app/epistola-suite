// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.TemplateNotInReleaseException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * [toProblemMap] flattens a [org.springframework.http.ProblemDetail]'s members onto the wire
 * object. A member a mapping leaves null for one occurrence is omitted, not a 500.
 */
class ProblemMapTest {

    private val request = MockHttpServletRequest("POST", "/api/tenants/acme/catalogs/epistola.app/acme/letters/templates/absent/validate")

    @Test
    fun `a null member is omitted, the others are written`() {
        val body = problemDetail(request, ApiProblemTypes.NOT_FOUND, "absent", mapOf("present" to "yes", "absent" to null)).toProblemMap()

        assertThat(body).containsEntry("present", "yes").doesNotContainKey("absent")
        assertThat(body).containsEntry("status", 404).containsEntry("detail", "absent")
    }

    @Test
    fun `a template missing from a release, which names no variant, maps to a problem`() {
        val ex = TemplateNotInReleaseException(TenantKey.of("acme"), CatalogKey.of("letters"), "1.0.0", TemplateKey.of("absent"), variantId = null)
        val mapping = ApiExceptionMappings.forException(ex)!!

        val body = problemDetail(request, mapping.problemType, mapping.detail(ex), mapping.extensions(ex)).toProblemMap()

        assertThat(body["status"]).isEqualTo(404)
        assertThat(body).containsEntry("templateKey", "absent").doesNotContainKey("variantKey")
    }
}
