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

/**
 * The catalog mirror while tenants are confidential (ADR 0052 §1): a release holds the templates
 * themselves, which name the client whatever key it is filed under, so the mirror is unavailable.
 * Becoming confidential forgets every tenant's choice and the preset ([ForgetCatalogMirrorSharing]);
 * the next reconcile withdraws everything the mirror holds. At startup the same happens when the
 * configuration makes tenants confidential while some still share, as after an upgrade.
 */
@Component
class CatalogMirrorConfidentiality(
    private val privacy: SupportPrivacy,
    private val jdbi: Jdbi,
) : TenantIdentityListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun becameConfidential() {
        ForgetCatalogMirrorSharing().execute()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun withdrawWhenConfidential() {
        // Read now, never a stand-in: before the settings were read the strictest apply, which would
        // forget every tenant's choice on an installation whose tenants are named.
        val settings = privacy.read() ?: return log.warn("Privacy settings could not be read at startup; the catalog mirror leaves every tenant's choice as it is")
        if (!settings.confidential) return
        val choices = jdbi.withHandle<Int, Exception> { it.createQuery("SELECT COUNT(*) FROM support_sharing_choice WHERE ${mirrorSharing()}").mapTo(Int::class.java).one() }
        if (choices == 0) return
        log.info("Tenants are confidential to Epistola: the catalog mirror forgets {} tenant choices and withdraws what it holds", choices)
        jdbi.useTransaction<Exception> { forgetMirrorSharing(it) }
    }
}
