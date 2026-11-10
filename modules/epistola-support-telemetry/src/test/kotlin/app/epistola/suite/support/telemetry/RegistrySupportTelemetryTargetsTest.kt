// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.connection.SupportConnection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

/** Which connections telemetry goes to, read from the installation's connections. */
class RegistrySupportTelemetryTargetsTest {
    @Test
    fun `a read that fails, as while the database is unreachable, keeps the targets the last read found`() {
        val connection = FakeSupportConnection(otlp = URI("https://support.example/otlp"), token = "t")
        var read: () -> List<SupportConnection> = { listOf(connection) }
        val targets = RegistrySupportTelemetryTargets({ read() }, privacy = null, references = null)
        val first = targets.targets()
        assertThat(first.map { it.connectionId }).containsExactly(connection.id)

        read = { error("database unreachable") }

        assertThat(targets.targets()).`as`("the same targets, so no leg is shut down").containsExactlyElementsOf(first)
    }

    @Test
    fun `a connection that is gone at the next read is dropped`() {
        val connection = FakeSupportConnection(otlp = URI("https://support.example/otlp"), token = "t")
        var read: () -> List<SupportConnection> = { listOf(connection) }
        val targets = RegistrySupportTelemetryTargets({ read() }, privacy = null, references = null)
        targets.targets()

        read = { emptyList() }

        assertThat(targets.targets()).isEmpty()
    }
}
