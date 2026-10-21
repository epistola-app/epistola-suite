// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.error.ProblemDetails
import app.epistola.suite.catalog.registry.NamespaceNotGrantedException
import app.epistola.suite.catalog.registry.RegistryArchiveRejectedException
import app.epistola.suite.catalog.registry.RegistryReleaseNotFoundException
import app.epistola.suite.catalog.registry.ReleaseNumberTakenException
import app.epistola.suite.catalog.registry.ReleaseRegistryConflictException
import app.epistola.suite.catalog.registry.ReleaseRegistryNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The release registry refusals answer the contract's problem types (ADR 0008), with its slugs and statuses. */
class ReleaseRegistryProblemMappingTest {

    private fun slugAndStatus(exception: Throwable): Pair<String, Int> {
        val type = requireNotNull(ApiExceptionMappings.forException(exception)) { "no mapping for ${exception.javaClass.simpleName}" }.problemType
        return type.slug to type.status.value()
    }

    @Test
    fun `each refusal maps to the contract's slug and status`() {
        assertThat(slugAndStatus(ReleaseRegistryNotFoundException("nowhere", listOf("local")))).isEqualTo(ProblemDetails.KnownSlugs.RELEASE_REGISTRY_NOT_FOUND to 404)
        assertThat(slugAndStatus(ReleaseNumberTakenException("taken"))).isEqualTo(ProblemDetails.KnownSlugs.RELEASE_NUMBER_TAKEN to 409)
        assertThat(slugAndStatus(NamespaceNotGrantedException("not granted"))).isEqualTo(ProblemDetails.KnownSlugs.NAMESPACE_NOT_GRANTED to 409)
        assertThat(slugAndStatus(RegistryReleaseNotFoundException("not held"))).isEqualTo(ProblemDetails.KnownSlugs.NOT_FOUND to 404)
        assertThat(slugAndStatus(RegistryArchiveRejectedException("not the catalog"))).isEqualTo(ProblemDetails.KnownSlugs.BAD_REQUEST to 400)
        assertThat(slugAndStatus(ReleaseRegistryConflictException("a draft"))).isEqualTo(ProblemDetails.KnownSlugs.CONFLICT to 409)
    }
}
