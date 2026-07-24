// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

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
