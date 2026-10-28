// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthTypeTest {

    @Test
    fun `an API key is sent with the ApiKey scheme an Epistola server reads, and as X-API-Key`() {
        assertThat(AuthType.API_KEY.headers("epk_secret")).containsExactly(
            entry("Authorization", "ApiKey epk_secret"),
            entry("X-API-Key", "epk_secret"),
        )
    }

    @Test
    fun `a bearer token is sent with the Bearer scheme`() {
        assertThat(AuthType.BEARER.headers("tok")).containsExactly(entry("Authorization", "Bearer tok"))
    }

    @Test
    fun `no authentication sends no header`() {
        assertThat(AuthType.NONE.headers(null)).isEmpty()
    }

    private fun entry(key: String, value: String) = java.util.Map.entry(key, value)
}
