// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReleaseProvenanceOfFileTest {
    @Test
    fun `an uploaded file's name is kept up to the limit`() {
        val long = "a".repeat(ReleaseProvenance.MAX_FROM + 40) + ".zip"

        assertThat(ReleaseProvenance.ofFile(ReleaseChannel.UPLOAD, long).from).hasSize(ReleaseProvenance.MAX_FROM)
        assertThat(ReleaseProvenance.ofFile(ReleaseChannel.API, "basis.zip")).isEqualTo(ReleaseProvenance(ReleaseChannel.API, "basis.zip"))
    }

    @Test
    fun `a blank or missing file name records no source`() {
        assertThat(ReleaseProvenance.ofFile(ReleaseChannel.UPLOAD, "  ").from).isNull()
        assertThat(ReleaseProvenance.ofFile(ReleaseChannel.UPLOAD, null).from).isNull()
    }
}
