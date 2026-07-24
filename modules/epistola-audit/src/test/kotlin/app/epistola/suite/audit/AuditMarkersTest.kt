// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

import app.epistola.suite.common.AuditedRead
import app.epistola.suite.common.NotAudited
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.queries.GetDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Fast guard for the deliberate audit-scope decisions, so a marker can't be
 * dropped silently:
 * - high-volume document generation is excluded from the audit log ([NotAudited]);
 * - retrieving a stored document is an audited data-access read ([AuditedRead]).
 */
class AuditMarkersTest {

    @Test
    fun `high-volume generation commands are excluded from audit`() {
        assertThat(NotAudited::class.java.isAssignableFrom(GenerateDocument::class.java)).isTrue()
        assertThat(NotAudited::class.java.isAssignableFrom(GenerateDocumentBatch::class.java)).isTrue()
    }

    @Test
    fun `document retrieval is an audited read`() {
        assertThat(AuditedRead::class.java.isAssignableFrom(GetDocument::class.java)).isTrue()
    }
}
