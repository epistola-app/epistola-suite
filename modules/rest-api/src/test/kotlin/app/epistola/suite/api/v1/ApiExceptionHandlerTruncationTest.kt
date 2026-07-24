// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.sql.BatchUpdateException
import java.sql.SQLException

/**
 * REST-side safety net for #692 (sibling of the UI-side `UiExceptionFilterTest`
 * for #608): an over-length value that slips past command validation and hits a
 * `VARCHAR(n)` column throws a PostgreSQL string-truncation (SQLSTATE 22001).
 * On the REST (`api`) surface this used to fall through to the catch-all → an
 * opaque 500. It must map to a 400 instead, staying a form-level message (22001
 * carries no column identity) and never leaking the raw driver text.
 *
 * The `api` surface is excluded from `UiExceptionFilter`, so this handler is the
 * only backstop for REST callers and for background/import flows surfaced there.
 */
class ApiExceptionHandlerTruncationTest {

    private val handler = ApiExceptionHandler()

    private fun request() = MockHttpServletRequest("POST", "/api/tenants/acme/catalogs")

    private fun truncationChain() = RuntimeException(
        // Mirrors the real JDBI batch-insert failure chain: JDBI's
        // UnableToExecuteStatementException (a plain RuntimeException) wrapping
        // pgjdbc's BatchUpdateException wrapping the driver-level SQLException,
        // with SQLSTATE 22001 on the SQL layers, as pgjdbc sets it.
        "Unable to execute statement",
        BatchUpdateException(
            "Batch entry 0 INSERT INTO themes (...) was aborted",
            "22001",
            intArrayOf(),
            SQLException("ERROR: value too long for type character varying(255)", "22001"),
        ),
    )

    @Test
    fun `string truncation (SQLSTATE 22001) maps to a 400, not the opaque 500`() {
        val response = handler.handleGenericException(truncationChain(), request())

        assertThat(response.statusCode.value()).isEqualTo(400)
        val body = response.body!!
        assertThat(body.detail).contains("too long")
        // Never leak the raw pgjdbc message (would expose column widths / internals).
        assertThat(body.detail).doesNotContain("value too long for type")
    }

    @Test
    fun `an unrelated error still maps to the opaque 500`() {
        val response = handler.handleGenericException(RuntimeException("boom"), request())

        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body!!.detail).doesNotContain("boom")
    }
}
