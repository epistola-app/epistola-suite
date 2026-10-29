// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The global default for a feature with no tenant override follows [FeatureDefaults]. `defaultFor`
 * does not touch the database, so this is a pure unit test (the JDBI dependency is an unused mock).
 */
class FeatureToggleDefaultsTest {
    private fun service() = FeatureToggleService(mock(Jdbi::class.java), FeatureDefaults())

    @Test
    fun `feedback is freely usable and defaults on`() {
        assertThat(service().defaultFor(KnownFeatures.SUPPORT_FEEDBACK)).isTrue()
    }
}
