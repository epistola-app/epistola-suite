// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantReferences
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where telemetry may go: a [SupportOtlpTarget] for every support connection selected for `telemetry`
 * (ADR 0053 §1: a service like any other, sent to every connection that grants it, each with its own
 * privacy). Whether anything is sent is still decided per export, by each connection's grant.
 */
fun interface SupportTelemetryTargets {
    /** The connections selected for telemetry now. Reads the connections' rows: never on a logging thread. */
    fun targets(): List<SupportOtlpTarget>
}

/**
 * [SupportTelemetryTargets] over the installation's connections ([SupportConnectionRegistry]). A target
 * is built once per connection and kept, so its outage logs carry over between reads; one for a
 * connection that is gone is dropped at the next read. Each target reads its privacy from
 * [SupportPrivacy.forConnection], which never touches the database, as the logging thread asks it.
 */
@Component
class RegistrySupportTelemetryTargets internal constructor(
    /** The installation's connections now; may fail while the database is unreachable. */
    private val connections: () -> List<SupportConnection>,
    private val privacy: SupportPrivacy?,
    private val references: TenantReferences?,
) : SupportTelemetryTargets {
    @Autowired
    constructor(registry: SupportConnectionRegistry, privacy: SupportPrivacy? = null, references: TenantReferences? = null) : this(registry::all, privacy, references)

    private val built = ConcurrentHashMap<UUID, SupportOtlpTarget>()

    /** What the last read found: kept while a read fails, so a database blip drops no leg and no queued logs. */
    @Volatile private var last: List<SupportOtlpTarget> = emptyList()

    override fun targets(): List<SupportOtlpTarget> = runCatching { read() }.getOrElse { last }.also { last = it }

    private fun read(): List<SupportOtlpTarget> {
        val connections = connections()
        built.keys.retainAll(connections.map { it.id }.toSet())
        return connections
            .map { connection ->
                built.computeIfAbsent(connection.id) { id ->
                    SupportOtlpTarget(
                        connection,
                        logPrivacy = { TelemetryPrivacy.of(settings(id), references) },
                        metricsRewrite = { data ->
                            val settings = settings(id)
                            MetricsPrivacy.apply(data, TelemetryPrivacy.of(settings, references), settings.confidential)
                        },
                    )
                }
            }
            .filter { it.selected() }
    }

    // Without the settings, the strictest apply.
    private fun settings(id: UUID) = privacy?.forConnection(id) ?: SupportPrivacy.STRICTEST
}
