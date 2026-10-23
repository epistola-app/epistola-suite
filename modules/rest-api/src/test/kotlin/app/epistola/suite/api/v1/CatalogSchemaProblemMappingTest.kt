// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.common.ids.CatalogKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

/**
 * The catalog wire-format migration exceptions map to dedicated RFC 9457
 * problem types with the version detail as extension members (Phase 2 —
 * operator-facing polish). The registry/handler wiring itself is guarded by
 * [ApiExceptionMappingsConsistencyTest]; this asserts the mapping *content*.
 */
class CatalogSchemaProblemMappingTest {

    @Test
    fun `too-new maps to CATALOG_SCHEMA_TOO_NEW with version extensions`() {
        val mapping = ApiExceptionMappings.forException(CatalogSchemaTooNewException(version = 9, current = 2))
        assertThat(mapping).isNotNull
        assertThat(mapping!!.problemType).isEqualTo(ApiProblemTypes.CATALOG_SCHEMA_TOO_NEW)
        assertThat(mapping.problemType.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(mapping.extensions(CatalogSchemaTooNewException(version = 9, current = 2)))
            .containsExactlyInAnyOrderEntriesOf(mapOf("version" to 9, "supportedVersion" to 2))
    }

    @Test
    fun `a release rendering with defaults this instance lacks is the contract's catalog-rendering-defaults-too-new`() {
        val exception = ReleaseRenderingDefaultsTooNewException(CatalogKey.of("invoices"), "1.1.0", "9", supported = 6)
        assertThat(ApiExceptionMappings.forException(exception)).`as`("built by the contract's helper, not the generic mapping").isNull()

        val response = ApiExceptionHandler().handleRenderingDefaultsTooNew(exception, MockHttpServletRequest("POST", "/api/tenants/acme/catalogs/import"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val problem = response.body!!
        assertThat(problem.type.toString()).isEqualTo("$PROBLEM_TYPE_BASE_URL/catalog-rendering-defaults-too-new")
        assertThat(problem.title).isEqualTo("Catalog Rendering Defaults Too New")
        assertThat(problem.instance.toString()).isEqualTo("/api/tenants/acme/catalogs/import")
        assertThat(problem.properties).containsExactlyInAnyOrderEntriesOf(mapOf("renderingDefaultsVersion" to "9", "supportedVersion" to 6))
    }

    @Test
    fun `too-old maps to CATALOG_SCHEMA_TOO_OLD with baseline extension`() {
        val mapping = ApiExceptionMappings.forException(CatalogSchemaTooOldException(version = 1, baseline = 4))
        assertThat(mapping).isNotNull
        assertThat(mapping!!.problemType).isEqualTo(ApiProblemTypes.CATALOG_SCHEMA_TOO_OLD)
        assertThat(mapping.extensions(CatalogSchemaTooOldException(version = 1, baseline = 4)))
            .containsExactlyInAnyOrderEntriesOf(mapOf("version" to 1, "baselineVersion" to 4))
    }

    @Test
    fun `unrecognised maps to CATALOG_SCHEMA_UNKNOWN with no extensions`() {
        val ex = CatalogSchemaUnknownException("missing 'schemaVersion'")
        val mapping = ApiExceptionMappings.forException(ex)
        assertThat(mapping).isNotNull
        assertThat(mapping!!.problemType).isEqualTo(ApiProblemTypes.CATALOG_SCHEMA_UNKNOWN)
        assertThat(mapping.extensions(ex)).isEmpty()
        assertThat(mapping.detail(ex)).contains("schemaVersion")
    }

    @Test
    fun `the dedicated problem types use the errors base URL`() {
        assertThat(ApiProblemTypes.CATALOG_SCHEMA_TOO_NEW.type.toString())
            .isEqualTo("$PROBLEM_TYPE_BASE_URL/catalog-schema-too-new")
        assertThat(ApiProblemTypes.CATALOG_SCHEMA_TOO_OLD.type.toString())
            .isEqualTo("$PROBLEM_TYPE_BASE_URL/catalog-schema-too-old")
        assertThat(ApiProblemTypes.CATALOG_SCHEMA_UNKNOWN.type.toString())
            .isEqualTo("$PROBLEM_TYPE_BASE_URL/catalog-schema-unknown")
    }
}
