// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException

/**
 * Ends this installation's connection, as an installation administrator asked: tells the support
 * service (`DELETE {api}/connection`), which deletes the catalog mirror and keeps the feedback for its
 * retention period, then forgets the registration and records the disconnect, so no check registers
 * again until an administrator registers ([DefaultSupportConnection.registerNow]). Clearing the
 * tenants' choices is [DisconnectSupportConnection]'s.
 */
@Component
class SupportDisconnector(
    private val client: SupportServiceClient,
    private val epistola: DefaultSupportConnection,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Disconnects as [by] (display name; null when unknown). With [localOnly], for when the service
     * cannot be reached, the service is not told: the registration and data stay there until Epistola
     * removes them. Allowed while pending, active or refused; a refused registration is not accepted
     * by the service, so it is not told either.
     *
     * @throws SupportUnavailableException when the service could not be told; nothing changed then.
     * @throws SupportRegistrationNotAllowedException when there is no connection to end.
     */
    fun disconnect(localOnly: Boolean, by: String?): SupportDisconnectOutcome = disconnect(epistola, localOnly, by)

    /** [disconnect] for [connection], any connection that registers through OAuth (RFC 7591, private_key_jwt). */
    fun disconnect(connection: DefaultSupportConnection, localOnly: Boolean, by: String?): SupportDisconnectOutcome {
        val store = connection.store
        if (!connection.settings.enabled) throw SupportUnavailableException(connection.settings.offReason)
        try {
            return store.tryExclusively {
                connection.invalidate()
                val state = connection.status().state
                if (state !is SupportConnectionState.Pending && state !is SupportConnectionState.Active && state !is SupportConnectionState.Refused) {
                    throw SupportRegistrationNotAllowedException("Disconnecting is only possible while registered, not while ${state.javaClass.simpleName}")
                }
                val outcome = when {
                    localOnly -> SupportDisconnectOutcome.LOCAL_ONLY
                    state is SupportConnectionState.Refused -> SupportDisconnectOutcome.ALREADY_GONE
                    else -> tellService(connection)
                }
                val now = EpistolaClock.instant()
                store.disconnected(
                    StoredSupportStatus(
                        issuer = store.status()?.issuer,
                        state = StoredConnectionState.DISCONNECTED,
                        checkedAt = now,
                        disconnectedAt = now,
                        disconnectedBy = by,
                        disconnectOutcome = outcome,
                    ),
                )
                connection.forgetNodeState()
                log.info("Support connection: disconnected by an installation administrator ({})", outcome)
                outcome
            } ?: throw SupportConnectionBusyException()
        } finally {
            connection.invalidate()
        }
    }

    /**
     * `DELETE {api}/connection` with this node's token; a `401` gets a new token once. A registration
     * the token endpoint refuses, or no longer knows, has nothing to end at the service.
     */
    private fun tellService(connection: DefaultSupportConnection): SupportDisconnectOutcome {
        val serviceMetadata = connection.serviceMetadata()
        val first = try {
            connection.tokenForCall()
        } catch (_: SupportRefusedException) {
            return SupportDisconnectOutcome.ALREADY_GONE
        }
        try {
            client.disconnect(serviceMetadata, first)
        } catch (e: SupportUnavailableException) {
            if ((e.cause as? RestClientResponseException)?.statusCode?.value() != UNAUTHORIZED) throw e
            val second = try {
                connection.replaceToken(first)
            } catch (_: SupportRefusedException) {
                return SupportDisconnectOutcome.ALREADY_GONE
            }
            client.disconnect(serviceMetadata, second)
        }
        return SupportDisconnectOutcome.CONFIRMED
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }
}
