// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogDraftUnchangedException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Publishes the next version of each of [catalogs] at once: in an order where every catalog comes after
 * the ones of the selection it depends on, each released as the publish dialog releases one (with the
 * drafts of other catalogs it builds on, first). A catalog with nothing left to publish, as one already
 * published along with an earlier one, is passed over. With [markReady], the changes of each catalog,
 * and of the drafts published along with it, are marked ready first, as publishing does for someone who
 * may mark them.
 *
 * Self-managed: each catalog's release stands on its own, so one refusal leaves the others published,
 * and a catalog depending on a refused one is not attempted.
 */
data class PublishCatalogs(
    override val tenantKey: TenantKey,
    val catalogs: List<CatalogId>,
    val markReady: Boolean = false,
) : Command<PublishCatalogsResult>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_RELEASE
}

/** What publishing did, per catalog, in the order it went through them. */
data class PublishCatalogsResult(val outcomes: List<CatalogPublishOutcome>) {
    val published: List<CatalogPublishOutcome> get() = outcomes.filter { it.kind == CatalogPublishOutcome.Kind.PUBLISHED }
}

/**
 * One catalog's outcome: [Kind.PUBLISHED] as [version] (with [alongWith], the other catalogs published
 * first for it); [Kind.UNCHANGED] when there was nothing to publish; [Kind.REFUSED] with [reason];
 * [Kind.NOT_ATTEMPTED] when a catalog it depends on was refused.
 */
data class CatalogPublishOutcome(
    val catalog: CatalogId,
    val kind: Kind,
    val version: String? = null,
    val reason: String? = null,
    val alongWith: List<String> = emptyList(),
) {
    enum class Kind { PUBLISHED, UNCHANGED, REFUSED, NOT_ATTEMPTED }
}

@Component
class PublishCatalogsHandler : CommandHandler<PublishCatalogs, PublishCatalogsResult> {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: PublishCatalogs): PublishCatalogsResult {
        val selection = command.catalogs.distinct()
        val dependsOn = selection.associateWith { catalog ->
            val declared = ListCatalogDependencies(catalog).query()
            selection.filter { other -> other != catalog && declared.any { it.catalogIn(command.tenantKey) == other } }.toSet()
        }
        val outcomes = mutableListOf<CatalogPublishOutcome>()
        val failed = mutableSetOf<CatalogId>()
        for (catalog in order(selection, dependsOn)) {
            val blockedBy = dependsOn.getValue(catalog).filter { it in failed }
            if (blockedBy.isNotEmpty()) {
                failed += catalog
                outcomes += CatalogPublishOutcome(catalog, CatalogPublishOutcome.Kind.NOT_ATTEMPTED, reason = "It uses ${blockedBy.joinToString { it.key.value }}, which was not published")
                continue
            }
            outcomes += try {
                if (command.markReady) markReady(catalog)
                val result = ReleaseCatalogDraft(catalog, releaseDependencies = true, requireChange = true).execute()
                CatalogPublishOutcome(catalog, CatalogPublishOutcome.Kind.PUBLISHED, version = result.version, alongWith = result.releasedDependencies.map { "${it.catalogKey} ${it.releaseVersion}" })
            } catch (_: CatalogDraftUnchangedException) {
                CatalogPublishOutcome(catalog, CatalogPublishOutcome.Kind.UNCHANGED)
            } catch (e: RuntimeException) {
                logger.info("Publishing {} along with others was refused: {}", catalog.address, e.message)
                failed += catalog
                CatalogPublishOutcome(catalog, CatalogPublishOutcome.Kind.REFUSED, reason = e.message ?: "It was refused")
            }
        }
        return PublishCatalogsResult(outcomes)
    }

    /** [catalog]'s changes, and those of the drafts publishing it publishes first, marked ready. */
    private fun markReady(catalog: CatalogId) {
        MarkCatalogReady(catalog).execute()
        GetCatalogBorrowing(catalog).query().publishedAlong.forEach { other ->
            val id = CatalogId(other.namespace, app.epistola.suite.common.ids.CatalogKey.of(other.catalogKey), app.epistola.suite.common.ids.TenantId(catalog.tenantKey))
            MarkCatalogReady(id, catalogVersion = CatalogReleaseRef(id, DraftRef.Id(requireNotNull(other.draftId)))).execute()
        }
    }

    /**
     * [selection] with every catalog after those of it that it depends on; the rest keep their order.
     * Catalogs depending on each other cannot be ordered and keep theirs; releasing refuses them.
     */
    private fun order(selection: List<CatalogId>, dependsOn: Map<CatalogId, Set<CatalogId>>): List<CatalogId> {
        val ordered = mutableListOf<CatalogId>()
        val visiting = mutableSetOf<CatalogId>()
        fun visit(catalog: CatalogId) {
            if (catalog in ordered || !visiting.add(catalog)) return
            dependsOn.getValue(catalog).forEach(::visit)
            visiting -= catalog
            ordered += catalog
        }
        selection.forEach(::visit)
        return ordered
    }
}
