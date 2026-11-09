// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.sharing

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportConnections
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One thing a tenant shares with Epistola, contributed by the module that sends it (feedback, the
 * catalog mirror). The tenant's Support page is the one place a tenant sets it: a setup wizard while
 * nothing is set up, with one step per section; an installation preset (`/platform/support`, or the
 * deployment configuration) sets it for every tenant without a choice of its own.
 *
 * Reads and writes go through the module's commands and queries, which enforce who may do what.
 * Every setting is per support connection (ADR 0053): [connectionId], by default the discovered one.
 */
interface TenantSharingSection {
    /** Form field `<key>.choice` in the wizard, and the `data-testid` suffix. */
    val key: String

    /** Position in the wizard and the summaries, lowest first. */
    val order: Int

    /** The wizard step's heading, such as "Feedback". */
    val title: String

    /** One sentence saying what is shared, for where the full [statementFragment] would be too much. */
    val summary: String

    /** `template :: fragment` with what is sent, shown in the wizard step and folded away on `/platform/support`. */
    val statementFragment: String

    /** What a tenant can choose, in the order offered. */
    val choices: List<SharingChoice>

    /** The installation may leave it to each tenant (no preset). False for a service a tenant should not be asked about. */
    val offersNoPreset: Boolean get() = true

    /**
     * The installation may make it Required, without a tenant choice ([SharingRestriction.REQUIRED]).
     * Only for a service that sends no tenant content: content needs the tenant's own opt-in (ADR 0050 §10).
     */
    val offersRequired: Boolean get() = false

    /**
     * Whether tenants are asked about it now: the installation selected the service. A service it does
     * not use is not offered, so no tenant agrees to share what is never sent.
     */
    fun offered(connectionId: UUID = SupportConnections.DISCOVERED_ID): Boolean = true

    /** What the tenant shares now, and whose decision that is. */
    fun tenantView(tenantKey: TenantKey, connectionId: UUID = SupportConnections.DISCOVERED_ID): TenantSharingView

    /** The installation preset. */
    fun installationView(connectionId: UUID = SupportConnections.DISCOVERED_ID): InstallationSharingView

    /** Records [value] (one of [choices]) as the tenant's own choice. */
    fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID = SupportConnections.DISCOVERED_ID)

    /** The tenant drops its own choice and follows the installation preset. */
    fun followPreset(tenantKey: TenantKey, connectionId: UUID = SupportConnections.DISCOVERED_ID)

    /** Applies the preset from [form] (all fields of the platform page's form); a notice of what changed, or null. */
    fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID = SupportConnections.DISCOVERED_ID): String?

    /**
     * Forgets what every tenant chose and the installation's preset, as the installation disconnected
     * from Epistola support: after registering again, every tenant is asked again. Stops what still
     * waits to be sent. Idempotent.
     */
    fun forgetAll(connectionId: UUID = SupportConnections.DISCOVERED_ID)
}

/**
 * One option of a section: the form [value] and the [label] a person reads. [shares]: choosing it
 * sends something to Epistola, so whoever chooses it agrees on the tenant's behalf.
 */
data class SharingChoice(val value: String, val label: String, val shares: Boolean)

/**
 * What a tenant shares for one section.
 *
 * @property value the choice in force now, one of [TenantSharingSection.choices].
 * @property followsPreset the tenant has no choice of its own and follows the installation preset.
 * @property decidedBy who made the decision in force (a person's name); null when unknown.
 * @property withheld choices that cannot be made now, with the reason and who can change that.
 */
data class TenantSharingView(
    val section: TenantSharingSection,
    val value: String,
    val followsPreset: Boolean,
    val decidedBy: String?,
    val decidedAt: OffsetDateTime?,
    val withheld: Map<String, String> = emptyMap(),
    /** How the choice stands now, when it is not simply in effect: shared, but nothing can be sent yet, and why. */
    val notice: String? = null,
) {
    /** The label of [value]. */
    val label: String get() = section.choices.firstOrNull { it.value == value }?.label ?: value
}

/**
 * The installation preset of one section.
 *
 * @property isSet a preset exists: saved by an installation administrator, or from the configuration.
 * @property value the preset as a tenant's choice; meaningful only while [isSet].
 */
data class InstallationSharingView(
    val section: TenantSharingSection,
    val isSet: Boolean,
    val value: String,
    val fromConfiguration: Boolean,
    val setBy: String?,
    val setAt: OffsetDateTime?,
    val restriction: SharingRestriction? = null,
) {
    val label: String get() = section.choices.firstOrNull { it.value == value }?.label ?: value

    /** No tenant may share this section ([SharingRestriction.NOT_ALLOWED]). */
    val notAllowed: Boolean get() = restriction == SharingRestriction.NOT_ALLOWED

    /** Every tenant shares this section, without a choice ([SharingRestriction.REQUIRED]). */
    val required: Boolean get() = restriction == SharingRestriction.REQUIRED

    /** The value the platform form shows as chosen: [NOT_ALLOWED], the preset, or [NO_PRESET]. */
    val formValue: String get() = when {
        notAllowed -> NOT_ALLOWED
        required -> REQUIRED
        isSet -> value
        else -> NO_PRESET
    }

    /** The form field of this preset on `/platform/support`. */
    val field: String get() = "${section.key}.default"
}

/** The form value of "no preset: each tenant sets it up itself" on `/platform/support`. */
const val NO_PRESET = "NONE"

/** The form value of "not allowed: no tenant shares this" on `/platform/support` (ADR 0052 §5). */
const val NOT_ALLOWED = "NOT_ALLOWED"

/** The form value of "required: every tenant shares this, without a choice" on `/platform/support` (ADR 0052 §5). */
const val REQUIRED = "REQUIRED"

/** The first value of form field [name], or null. */
fun Map<String, List<String>>.field(name: String): String? = this[name]?.firstOrNull()
