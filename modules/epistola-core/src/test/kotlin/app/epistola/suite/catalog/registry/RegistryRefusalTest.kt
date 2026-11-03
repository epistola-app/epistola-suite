// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A registry refusal answers the same through the release registry and a subscribed catalog's source. */
class RegistryRefusalTest {

    private fun refusal(code: ValidationCode) = ValidationException(field = "archive", message = "refused", code = code)

    @Test
    fun `registry refusals become release registry refusals`() {
        assertThat(registryRefusal(refusal(ValidationCode.REGISTRY_ARCHIVE_REJECTED), "local")).isInstanceOf(RegistryArchiveRejectedException::class.java)
        assertThat(registryRefusal(refusal(ValidationCode.REGISTRY_RELEASE_UNAVAILABLE), "local")).isInstanceOf(RegistryReleaseNotFoundException::class.java)
        assertThat(registryRefusal(refusal(ValidationCode.REGISTRY_TAG_TAKEN), "local")).isInstanceOf(ReleaseNumberTakenException::class.java)
        assertThat(registryRefusal(refusal(ValidationCode.REGISTRY_CATALOG_KEY_TAKEN), "local")).isInstanceOf(ReleaseRegistryConflictException::class.java)
        assertThat(registryRefusal(refusal(ValidationCode.REGISTRY_UNKNOWN), "elsewhere"))
            .isInstanceOfSatisfying(ReleaseRegistryNotFoundException::class.java) { assertThat(it.name).isEqualTo("elsewhere") }
    }

    @Test
    fun `any other validation error passes as it is`() {
        val generic = refusal(ValidationCode.GENERIC)
        assertThat(registryRefusal(generic, "local")).isSameAs(generic)
    }
}
