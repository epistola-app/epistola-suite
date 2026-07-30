// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CatalogUpgradeConflictException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class CatalogOperationProblemMappingTest {

    @Test
    fun `invalid release versions map to a client error`() {
        val mapping = ApiExceptionMappings.forException(
            CatalogReleaseVersionException("Version not-semver is invalid"),
        )!!

        assertThat(mapping.problemType.code).isEqualTo("CATALOG_RELEASE_VERSION_INVALID")
        assertThat(mapping.problemType.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(mapping.detail(CatalogReleaseVersionException("Version not-semver is invalid")))
            .contains("not-semver")
    }

    @Test
    fun `upgrade conflicts expose the blocking resources`() {
        val exception = CatalogUpgradeConflictException(listOf("theme/default", "stencil/header"))
        val mapping = ApiExceptionMappings.forException(exception)!!

        assertThat(mapping.problemType.code).isEqualTo("CATALOG_UPGRADE_CONFLICT")
        assertThat(mapping.problemType.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(mapping.extensions(exception)["conflicts"])
            .isEqualTo(listOf("theme/default", "stencil/header"))
    }
}
