// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogPublicationPolicyTest {
    @Test
    fun `hard policies cannot be overridden`() {
        assertThat(CatalogPublicationPolicy.ALWAYS.allowsReleaseOverride()).isFalse()
        assertThat(CatalogPublicationPolicy.ALWAYS.resolve(false, false)).isTrue()
        assertThat(CatalogPublicationPolicy.NEVER.allowsReleaseOverride()).isFalse()
        assertThat(CatalogPublicationPolicy.NEVER.resolve(true, true)).isFalse()
    }

    @Test
    fun `inherited and default policies accept a release override`() {
        assertThat(CatalogPublicationPolicy.INHERIT.resolve(true, null)).isTrue()
        assertThat(CatalogPublicationPolicy.INHERIT.resolve(false, true)).isTrue()
        assertThat(CatalogPublicationPolicy.DEFAULT_YES.resolve(false, null)).isTrue()
        assertThat(CatalogPublicationPolicy.DEFAULT_YES.resolve(false, false)).isFalse()
        assertThat(CatalogPublicationPolicy.DEFAULT_NO.resolve(true, null)).isFalse()
        assertThat(CatalogPublicationPolicy.DEFAULT_NO.resolve(true, true)).isTrue()
    }
}
