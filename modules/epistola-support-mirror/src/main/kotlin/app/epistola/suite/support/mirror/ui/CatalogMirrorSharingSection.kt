// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.mirror.CatalogMirror
import app.epistola.suite.support.mirror.CatalogMirrorAvailability
import app.epistola.suite.support.mirror.CatalogMirrorDefaultChoice
import app.epistola.suite.support.mirror.ClearCatalogMirrorDefault
import app.epistola.suite.support.mirror.FollowCatalogMirrorDefault
import app.epistola.suite.support.mirror.ForgetCatalogMirrorSharing
import app.epistola.suite.support.mirror.GetCatalogMirrorDefault
import app.epistola.suite.support.mirror.GetCatalogMirrorSettings
import app.epistola.suite.support.mirror.OptInCatalogMirror
import app.epistola.suite.support.mirror.OptOutCatalogMirror
import app.epistola.suite.support.mirror.SetCatalogMirrorDefault
import app.epistola.suite.support.privacy.SupportPrivacy
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
class CatalogMirrorSharingSection(
    private val registry: SupportConnectionRegistry,
    private val connections: SupportConnections,
    private val privacy: SupportPrivacy,
) : TenantSharingSection {
    override val key = "mirror"
    override val order = 20
    override val title = "Catalogs"
    override val summary = "Released catalog versions, with everything they depend on, go to the support connection to check them against new suite versions."
    override val statementFragment = "support/mirror/page :: statement"
    override val choices = listOf(
        SharingChoice(CatalogMirrorDefaultChoice.ALL.name, "Share every catalog, unless marked never to be shared", shares = true),
        SharingChoice(CatalogMirrorDefaultChoice.MARKED.name, "Share only the catalogs marked to share", shares = true),
        SharingChoice(CatalogMirrorDefaultChoice.OFF.name, "Do not share catalogs", shares = false),
    )

    /** Asked for every connection the installation selected the mirror for under Services (ADR 0053 step 3i). */
    override fun offered(connectionId: UUID): Boolean = registry.find(connectionId)?.isSelected(SupportScope.MIRROR) == true

    override fun tenantView(tenantKey: TenantKey, connectionId: UUID): TenantSharingView {
        val settings = GetCatalogMirrorSettings(tenantKey, limit = 1, connectionId = connectionId).query()
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
            "Not available: this installation keeps its tenants confidential to ${name(connectionId)}, and a shared release would name the organisation."
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
        val default = GetCatalogMirrorDefault(connectionId).query()
        // A preset that shares catalogs is refused while tenants are confidential (SetCatalogMirrorDefault), so it is not offered.
        val withheld = if (privacy.forConnection(connectionId).confidential) {
            val reason = "Not available: this installation keeps its tenants confidential to ${name(connectionId)}, and a shared release would name the organisation."
            choices.filter { it.shares }.associate { it.value to reason }
        } else {
            emptyMap()
        }
        return InstallationSharingView(this, default.isSet, default.choice.name, default.fromConfiguration, default.setBy, default.setAt, withheld = withheld)
    }

    override fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID) {
        when (CatalogMirrorDefaultChoice.valueOf(value)) {
            CatalogMirrorDefaultChoice.ALL -> OptInCatalogMirror(tenantKey, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = connectionId).execute()
            CatalogMirrorDefaultChoice.MARKED -> OptInCatalogMirror(tenantKey, shareByDefault = false, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = connectionId).execute()
            CatalogMirrorDefaultChoice.OFF -> OptOutCatalogMirror(tenantKey, connectionId).execute()
        }
    }

    override fun followPreset(tenantKey: TenantKey, connectionId: UUID) {
        FollowCatalogMirrorDefault(tenantKey, connectionId).execute()
    }

    override fun forgetAll(connectionId: UUID) {
        ForgetCatalogMirrorSharing(connectionId).execute()
    }

    override fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID): String? {
        val current = GetCatalogMirrorDefault(connectionId).query()
        if (form.field("mirror.default") == NO_PRESET) {
            if (!current.isSet) return null
            ClearCatalogMirrorDefault(connectionId).execute()
            return "Catalogs: no preset; each tenant sets it up."
        }
        val choice = form.field("mirror.default")?.let { runCatching { CatalogMirrorDefaultChoice.valueOf(it) }.getOrNull() } ?: return null
        if (current.isSet && !current.fromConfiguration && current.choice == choice) return null
        SetCatalogMirrorDefault(choice, connectionId).execute()
        return "Catalogs: ${choice.label.lowercase()}."
    }

    private fun name(connectionId: UUID): String = connections.find(connectionId)?.name ?: "this support connection"
}
