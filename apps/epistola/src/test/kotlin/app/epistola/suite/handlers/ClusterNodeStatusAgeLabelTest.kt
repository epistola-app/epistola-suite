// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.cluster.ClusterNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The Cluster page shows a relative age per node. Because nothing ever removed dead nodes,
 * that age routinely ran into weeks — and a minutes-only format rendered it as five-figure
 * minutes ("33474m ago"), which no operator can read at a glance.
 */
class ClusterNodeStatusAgeLabelTest {

    @ParameterizedTest(name = "{0}s reads as {1}")
    @CsvSource(
        "0, just now",
        "1, 1s ago",
        "59, 59s ago",
        "60, 1m ago",
        "3599, 59m ago",
        "3600, 1h ago",
        "3660, 1h 1m ago",
        "7380, 2h 3m ago",
        "86399, 23h 59m ago",
        "86400, 1d ago",
        "90000, 1d 1h ago",
        "2008440, 23d 5h ago",
    )
    fun `renders a two-unit relative age`(ageSeconds: Long, expected: String) {
        assertThat(ageLabelFor(ageSeconds)).isEqualTo(expected)
    }

    @Test
    fun `a month-old node reads in days, not five-figure minutes`() {
        // 2 008 440s is the "33474m ago" an operator saw on the Cluster page.
        val label = ageLabelFor(2_008_440)

        assertThat(label).isEqualTo("23d 5h ago")
        assertThat(label).doesNotContain("m ago")
    }

    private fun ageLabelFor(ageSeconds: Long): String = ClusterNodeStatus(
        node = ClusterNode(
            nodeId = "node",
            capabilities = listOf("suite"),
            version = "1.1.0",
            joinedAt = EPOCH,
            lastSeenAt = EPOCH,
        ),
        isCurrent = false,
        isActive = false,
        ageSeconds = ageSeconds,
    ).ageLabel

    private companion object {
        val EPOCH: OffsetDateTime = OffsetDateTime.of(2026, 6, 10, 0, 0, 0, 0, ZoneOffset.UTC)
    }
}
