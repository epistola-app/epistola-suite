// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReturnUrlsTest {
    private val id = GenerationRequestKey.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))

    @Test
    fun `adds the two parameters to a URL without a query`() {
        assertThat(ReturnUrls.withOutcome("https://zac.example.org/zaken/Z-1", id, "pending"))
            .isEqualTo("https://zac.example.org/zaken/Z-1?epistolaRequestId=550e8400-e29b-41d4-a716-446655440000&epistolaRequestStatus=pending")
    }

    @Test
    fun `keeps the caller's own parameters, including a status of its own, and the fragment`() {
        assertThat(ReturnUrls.withOutcome("https://zac.example.org/z?tab=docs&status=open#letters", id, "cancelled"))
            .isEqualTo("https://zac.example.org/z?tab=docs&status=open&epistolaRequestId=550e8400-e29b-41d4-a716-446655440000&epistolaRequestStatus=cancelled#letters")
    }

    @Test
    fun `replaces parameters of the same name, so what arrives is never ambiguous`() {
        assertThat(ReturnUrls.withOutcome("https://zac.example.org/z?epistolaRequestStatus=forged&a=1&epistolaRequestId=x", id, "pending"))
            .isEqualTo("https://zac.example.org/z?a=1&epistolaRequestId=550e8400-e29b-41d4-a716-446655440000&epistolaRequestStatus=pending")
    }
}
