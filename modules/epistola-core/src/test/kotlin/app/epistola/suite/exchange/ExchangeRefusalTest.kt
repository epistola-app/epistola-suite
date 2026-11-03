// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.registry.RegistryArchiveRejectedException
import app.epistola.suite.catalog.registry.RegistryReleaseNotFoundException
import app.epistola.suite.catalog.registry.ReleaseRegistryConflictException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * An Exchange refusal answers the same through the release registry (`installRegistryRelease`) and
 * through a subscribed catalog's source (`installCatalogRelease`), which used to pass it on as a
 * validation error of the request.
 */
class ExchangeRefusalTest {

    private fun refusal(code: ValidationCode) = ValidationException(field = "archive", message = "refused", code = code)

    @Test
    fun `the source's refusals become release registry refusals`() {
        assertThatThrownBy { translate { throw refusal(ValidationCode.EXCHANGE_ARCHIVE_REJECTED) } }
            .isInstanceOf(RegistryArchiveRejectedException::class.java)
        assertThatThrownBy { translate { throw refusal(ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE) } }
            .isInstanceOf(RegistryReleaseNotFoundException::class.java)
        assertThatThrownBy { translate { throw refusal(ValidationCode.EXCHANGE_CATALOG_KEY_TAKEN) } }
            .isInstanceOf(ReleaseRegistryConflictException::class.java)
        assertThatThrownBy { translate { throw refusal(ValidationCode.EXCHANGE_INSTALL_UNAVAILABLE) } }
            .isInstanceOf(ReleaseRegistryConflictException::class.java)
    }

    @Test
    fun `a validation error that is not an Exchange refusal passes as it is`() {
        val generic = refusal(ValidationCode.GENERIC)
        assertThatThrownBy { translate { throw generic } }.isSameAs(generic)
        assertThat(exchangeRefusal(generic)).isSameAs(generic)
    }
}
