// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.mediator.execute
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantIdentityListener
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The catalog mirror while tenants are confidential to a connection (ADR 0052 §1, per connection by
 * ADR 0053): a release holds the templates themselves, which name the client whatever key it is filed
 * under, so the mirror is unavailable over that connection. Becoming confidential to a connection
 * forgets every tenant's choice with it and its preset ([ForgetCatalogMirrorSharing]); its next
 * reconcile withdraws everything its mirror holds. At startup the same happens for every connection
 * the configuration makes tenants confidential to while some still share, as after an upgrade.
 */
@Component
class CatalogMirrorConfidentiality(
    private val privacy: SupportPrivacy,
    private val jdbi: Jdbi,
) : TenantIdentityListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun becameConfidential(connectionId: UUID) {
        ForgetCatalogMirrorSharing(connectionId).execute()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun withdrawWhenConfidential() {
        // Read now, never a stand-in: before the settings were read the strictest apply, which would
        // forget every tenant's choice on an installation whose tenants are named.
        privacy.read() ?: return log.warn("Privacy settings could not be read at startup; the catalog mirror leaves every tenant's choice as it is")
        val shared = jdbi.withHandle<Map<UUID, Int>, Exception> { handle ->
            handle.createQuery("SELECT connection_id, COUNT(*) AS choices FROM support_sharing_choice WHERE section = '${CatalogMirror.SECTION}' GROUP BY connection_id")
                .map { rs, _ -> rs.getObject("connection_id", UUID::class.java) to rs.getInt("choices") }
                .list()
                .toMap()
        }
        shared.filterKeys { privacy.forConnection(it).confidential }.forEach { (connectionId, choices) ->
            log.info("Tenants are confidential to a support connection: the catalog mirror forgets its tenant choices and withdraws what it holds connection={} choices={}", connectionId, choices)
            jdbi.useTransaction<Exception> { forgetMirrorSharing(it, connectionId) }
        }
    }
}
