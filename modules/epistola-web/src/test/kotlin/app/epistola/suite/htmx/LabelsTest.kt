// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LabelsTest {

    private enum class Status { IN_PROGRESS, OPEN }

    @Test
    fun `a code reads as words`() {
        assertThat(Labels.of(Status.IN_PROGRESS)).isEqualTo("In progress")
        assertThat(Labels.of(Status.OPEN)).isEqualTo("Open")
        assertThat(Labels.of("CONTENT_VIEWER")).isEqualTo("Content viewer")
        assertThat(Labels.of("content-viewer")).isEqualTo("Content viewer")
        assertThat(Labels.of(null)).isEmpty()
    }
}
