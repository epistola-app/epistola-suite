// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.mirror.CatalogMirror
import app.epistola.suite.support.mirror.CatalogMirrorAvailability
import app.epistola.suite.support.mirror.CatalogMirrorDefaultChoice
import app.epistola.suite.support.mirror.ClearCatalogMirrorDefault
import app.epistola.suite.support.mirror.FollowCatalogMirrorDefault
import app.epistola.suite.support.mirror.ForgetCatalogMirrorSharing
import app.epistola.suite.support.mirror.GetCatalogMirrorDefault
import app.epistola.suite.support.mirror.GetCatalogMirrorSettings
import app.epistola.suite.support.mirror.MIRROR_CONNECTION
import app.epistola.suite.support.mirror.OptInCatalogMirror
import app.epistola.suite.support.mirror.OptOutCatalogMirror
import app.epistola.suite.support.mirror.SetCatalogMirrorDefault
import app.epistola.suite.support.sharing.InstallationSharingView
import app.epistola.suite.support.sharing.NO_PRESET
import app.epistola.suite.support.sharing.SharingChoice
import app.epistola.suite.support.sharing.TenantSharingSection
import app.epistola.suite.support.sharing.TenantSharingView
import app.epistola.suite.support.sharing.field
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The catalog mirror as a step of the tenant's setup and as part of the installation preset. Which
 * catalogs are marked is set per catalog on the Catalog mirror page.
 */
@Component
class CatalogMirrorSharingSection(private val connection: SupportConnection) : TenantSharingSection {
    override val key = "mirror"
    override val order = 20
    override val title = "Catalogs"
    override val summary = "Released catalog versions, with everything they depend on, go to Epistola support to check them against new suite versions."
    override val statementFragment = "support/mirror/page :: statement"
    override val choices = listOf(
        SharingChoice(CatalogMirrorDefaultChoice.ALL.name, "Share every catalog, unless marked never to be shared", shares = true),
        SharingChoice(CatalogMirrorDefaultChoice.MARKED.name, "Share only the catalogs marked to share", shares = true),
        SharingChoice(CatalogMirrorDefaultChoice.OFF.name, "Do not share catalogs", shares = false),
    )

    /** Asked only once the installation selected the mirror under Services, and over Epistola support's connection only: the mirror is a service Epistola offers. */
    override fun offered(connectionId: UUID): Boolean = connectionId == MIRROR_CONNECTION && connection.isSelected(SupportScope.MIRROR)

    override fun tenantView(tenantKey: TenantKey, connectionId: UUID): TenantSharingView {
        onlyMirrorConnection(connectionId)
        val settings = GetCatalogMirrorSettings(tenantKey, limit = 1).query()
        val consent = settings.consent
        val value = when {
            consent == null || !consent.sharing -> CatalogMirrorDefaultChoice.OFF
            consent.shareByDefault -> CatalogMirrorDefaultChoice.ALL
            else -> CatalogMirrorDefaultChoice.MARKED
        }
        // Sharing needs the mirror on in this installation; otherwise only "do not share" can be chosen.
        val confidential = settings.availability == CatalogMirrorAvailability.CONFIDENTIAL
        val off = confidential || settings.availability == CatalogMirrorAvailability.SUPPORT_OFF || settings.availability == CatalogMirrorAvailability.MIRROR_OFF
        val reason = if (confidential) {
            "Not available: this installation keeps its tenants confidential to Epistola, and a shared release would name the organisation."
        } else {
            "This installation does not keep a catalog mirror; an installation administrator can turn it on."
        }
        return TenantSharingView(
            section = this,
            value = value.name,
            followsPreset = settings.followsDefault,
            decidedBy = consent?.optedInBy?.takeIf { consent.sharing },
            decidedAt = if (consent?.sharing == true) consent.optedInAt else consent?.optedOutAt,
            withheld = if (off) mapOf(CatalogMirrorDefaultChoice.ALL.name to reason, CatalogMirrorDefaultChoice.MARKED.name to reason) else emptyMap(),
        )
    }

    override fun installationView(connectionId: UUID): InstallationSharingView {
        onlyMirrorConnection(connectionId)
        val default = GetCatalogMirrorDefault().query()
        return InstallationSharingView(this, default.isSet, default.choice.name, default.fromConfiguration, default.setBy, default.setAt)
    }

    override fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID) {
        onlyMirrorConnection(connectionId)
        when (CatalogMirrorDefaultChoice.valueOf(value)) {
            CatalogMirrorDefaultChoice.ALL -> OptInCatalogMirror(tenantKey, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION).execute()
            CatalogMirrorDefaultChoice.MARKED -> OptInCatalogMirror(tenantKey, shareByDefault = false, consentVersion = CatalogMirror.CONSENT_VERSION).execute()
            CatalogMirrorDefaultChoice.OFF -> OptOutCatalogMirror(tenantKey).execute()
        }
    }

    override fun followPreset(tenantKey: TenantKey, connectionId: UUID) {
        onlyMirrorConnection(connectionId)
        FollowCatalogMirrorDefault(tenantKey).execute()
    }

    override fun forgetAll(connectionId: UUID) {
        onlyMirrorConnection(connectionId)
        ForgetCatalogMirrorSharing().execute()
    }

    override fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID): String? {
        onlyMirrorConnection(connectionId)
        val current = GetCatalogMirrorDefault().query()
        if (form.field("mirror.default") == NO_PRESET) {
            if (!current.isSet) return null
            ClearCatalogMirrorDefault().execute()
            return "Catalogs: no preset; each tenant sets it up."
        }
        val choice = form.field("mirror.default")?.let { runCatching { CatalogMirrorDefaultChoice.valueOf(it) }.getOrNull() } ?: return null
        if (current.isSet && !current.fromConfiguration && current.choice == choice) return null
        SetCatalogMirrorDefault(choice).execute()
        return "Catalogs: ${choice.label.lowercase()}."
    }

    /** The catalog mirror shares over one connection for now (ADR 0053). */
    private fun onlyMirrorConnection(connectionId: UUID) {
        check(connectionId == MIRROR_CONNECTION) { "The catalog mirror is shared over connection $MIRROR_CONNECTION only, not $connectionId" }
    }
}
