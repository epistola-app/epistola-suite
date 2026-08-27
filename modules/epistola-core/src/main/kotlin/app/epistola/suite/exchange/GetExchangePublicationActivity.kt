// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Publication across the whole tenant, rather than one catalog at a time.
 *
 * Per-catalog history answers "what happened to this catalog"; it cannot answer "is anything
 * wrong". Publication is asynchronous and silent by design — a release succeeds locally whatever
 * Exchange does — so an administrator whose enrollment lapsed has no signal at all until they open
 * the right catalog. This is that signal.
 */
data class ExchangePublicationActivity(
    val countsByStatus: Map<CatalogPublicationStatus, Int>,
    /** Most recently touched publications across every catalog. */
    val recent: List<CatalogReleasePublication>,
    /** The longest-outstanding unfinished publication, if any. */
    val oldestActive: OldestActivePublication?,
) {
    val total: Int get() = countsByStatus.values.sum()

    val active: Int get() = countsByStatus.filterKeys(CatalogPublicationStatus::isActive).values.sum()

    val failed: Int get() = countsByStatus[CatalogPublicationStatus.FAILED] ?: 0

    val rejected: Int get() = countsByStatus[CatalogPublicationStatus.REJECTED] ?: 0

    val accepted: Int get() = countsByStatus[CatalogPublicationStatus.ACCEPTED] ?: 0

    val oldestActiveSince: OffsetDateTime? get() = oldestActive?.since

    /**
     * Waiting is normal — a queued release moves within seconds once enrollment is complete — so
     * anything still unfinished after this long is a configuration or credential problem rather
     * than a slow Exchange, and is worth saying out loud.
     */
    val stalled: Boolean get() = (oldestActive?.age ?: Duration.ZERO) > STALL_THRESHOLD

    /** Non-empty states in lifecycle order, as a named type the view can address by property. */
    val breakdown: List<PublicationStatusCount>
        get() = CatalogPublicationStatus.entries.mapNotNull { status ->
            countsByStatus[status]?.takeIf { it > 0 }?.let { PublicationStatusCount(status, it) }
        }

    private companion object {
        val STALL_THRESHOLD: Duration = Duration.ofHours(1)
    }
}

data class PublicationStatusCount(val status: CatalogPublicationStatus, val count: Int) {
    val label: String get() = status.label
}

data class GetExchangePublicationActivity(
    override val tenantKey: TenantKey,
    val limit: Int = 25,
) : Query<ExchangePublicationActivity>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class GetExchangePublicationActivityHandler(
    private val store: CatalogPublicationStore,
) : QueryHandler<GetExchangePublicationActivity, ExchangePublicationActivity> {
    // Counting and ordering happen in the database; nothing is sorted or truncated in memory.
    override fun handle(query: GetExchangePublicationActivity) = ExchangePublicationActivity(
        countsByStatus = store.countsByStatus(query.tenantKey),
        recent = store.recent(query.tenantKey, query.limit),
        oldestActive = store.oldestActive(query.tenantKey),
    )
}
