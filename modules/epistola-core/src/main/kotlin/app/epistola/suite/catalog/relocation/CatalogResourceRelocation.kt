// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.MissingDependencyDeclarer
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.AuditDetailed
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

data class ResourceMoveBlocker(
    val code: String,
    val message: String,
    /** The relocation this blocks, or null when it is a property of the batch as a whole. */
    val source: ResourceAddress? = null,
)

/**
 * Something the operator should know before applying, which does not stop the move.
 *
 * The distinction is whether the suite can still guarantee a correct result. A blocker means it
 * cannot -- the move would corrupt something or could not be applied at all. A warning means the
 * move is well-defined here but has a consequence beyond this installation, which only the
 * operator can judge.
 */
data class ResourceMoveWarning(
    val code: String,
    val message: String,
    /** The relocation this concerns, or null when it is a property of the batch as a whole. */
    val source: ResourceAddress? = null,
)

/**
 * One resource's destination: a full address, so a relocation can change the catalog, the key, or
 * both.
 *
 * Moving and renaming are the same operation — both change the address while identity stays put —
 * so separating them would mean two commands with the same rewriting and validation. Carrying the
 * key here also gives a collision somewhere to go: a resource whose key is already taken in the
 * destination can land under a different one, where a catalog-only move could only be blocked.
 */
data class ResourceRelocation(
    val source: ResourceAddress,
    val target: ResourceAddress,
) {
    init {
        require(source.type == target.type) { "A relocation cannot change a resource's type" }
    }
}

/** The common case: move a resource to another catalog, keeping its key. */
fun ResourceAddress.movedTo(catalogKey: CatalogKey) = ResourceRelocation(this, copy(catalogKey = catalogKey.value))

/** Move a resource to another catalog under a different key. */
fun ResourceAddress.movedTo(catalogKey: CatalogKey, key: String) = ResourceRelocation(this, copy(catalogKey = catalogKey.value, key = key))

/** Keep a resource where it is under a different key -- a rename. */
fun ResourceAddress.renamedTo(key: String) = ResourceRelocation(this, copy(key = key))

/** What one relocation in a batch does. */
data class ResourceRelocationPlan(
    val source: ResourceAddress,
    val target: ResourceAddress,
    val resourceId: ResourceIdentity?,
    val mutableRewriteCount: Int,
    val immutableReferenceCount: Int,
)

/**
 * What a move means for the next release of one [catalog] (ADR 0042): the [kind] that release becomes
 * through it, and why ("no longer contains theme 'house'", "gains theme 'house'", "now depends on
 * shared").
 */
data class ReleaseConsequence(
    /** The catalog's address, `namespace/key`. */
    val catalog: String,
    val kind: ChangeKind,
    val reasons: List<String>,
)

/**
 * The batch as a whole.
 *
 * A batch is all-or-nothing: one transaction, one fingerprint, and any blocker stops every member.
 * Partial application would leave a half-reorganised tenant with no record of what was intended,
 * and the cycle check is only meaningful for the whole set anyway — moving several resources
 * together is precisely how an author resolves a cycle that any single move would be blocked on.
 */
data class CatalogResourceMovePreview(
    val relocations: List<ResourceRelocationPlan>,
    val blockers: List<ResourceMoveBlocker>,
    val warnings: List<ResourceMoveWarning>,
    val planFingerprint: String,
    /** Every catalog the move changes, in [releaseOrder], with what it means for its next release. */
    val releases: List<ReleaseConsequence> = emptyList(),
    /**
     * The catalogs the move changes, in the order they are released: a catalog before those that now
     * depend on it, which releasing with dependencies carries out.
     */
    val releaseOrder: List<String> = emptyList(),
) {
    val executable: Boolean get() = blockers.isEmpty()
    val mutableRewriteCount: Int get() = relocations.sumOf { it.mutableRewriteCount }
    val immutableReferenceCount: Int get() = relocations.sumOf { it.immutableReferenceCount }
}

/**
 * What moving [relocations] would do, without doing it. A move changes one draft per catalog (ADR
 * 0042): [catalogVersion] in its own catalog, which must be the catalog of one of the resources moved,
 * and the next version of every other catalog involved; null means the next version everywhere.
 */
data class PreviewCatalogResourceMove(
    override val tenantKey: TenantKey,
    val relocations: List<ResourceRelocation>,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Query<CatalogResourceMovePreview>,
    CatalogReleaseScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW

    init {
        validateDraftOfSource(tenantKey, relocations, catalogVersion)
    }
}

/** Moves [relocations] as [PreviewCatalogResourceMove] showed it, in the same drafts. */
data class MoveCatalogResources(
    override val tenantKey: TenantKey,
    val relocations: List<ResourceRelocation>,
    val expectedPlanFingerprint: String,
    override val catalogVersion: CatalogReleaseRef? = null,
) : Command<CatalogResourceMovePreview>,
    CatalogReleaseScoped,
    RequiresPermission,
    AuditDetailed {
    override val permission get() = Permission.CATALOG_MANAGE

    init {
        validateDraftOfSource(tenantKey, relocations, catalogVersion)
    }

    /** Each resource's old address and the one it moved to: where a resource went, and when. */
    override val auditDetails: Map<String, String> get() = relocations.associate { it.source.id to it.target.id }
}

/** The draft a move changes in its own catalog is of a catalog a resource moves out of. */
private fun validateDraftOfSource(tenantKey: TenantKey, relocations: List<ResourceRelocation>, catalogVersion: CatalogReleaseRef?) {
    if (catalogVersion == null) return
    validate("catalogVersion", catalogVersion.tenantKey == tenantKey && relocations.any { it.source.catalogIn(tenantKey) == catalogVersion.catalog }) {
        "The draft to work in must be of a catalog a resource moves out of"
    }
}

class CatalogResourceMoveBlockedException(
    val blockers: List<ResourceMoveBlocker>,
) : IllegalStateException(blockers.joinToString("; ") { it.message })

class StaleCatalogResourceMovePlanException : IllegalStateException("The catalog resource move plan is stale; preview it again")

@Component
class PreviewCatalogResourceMoveHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
) : QueryHandler<PreviewCatalogResourceMove, CatalogResourceMovePreview> {
    override fun handle(query: PreviewCatalogResourceMove): CatalogResourceMovePreview {
        // One snapshot for every read of the plan; a preview targeting a draft already runs in the
        // transaction that targets it, and reads in that.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return jdbi.inTransaction<CatalogResourceMovePreview, Exception> { handle -> planner.build(handle, query.tenantKey, query.relocations).preview }
        }
        return jdbi.inTransaction<CatalogResourceMovePreview, Exception>(TransactionIsolationLevel.REPEATABLE_READ) { handle ->
            planner.build(handle, query.tenantKey, query.relocations).preview
        }
    }
}

@Component
class MoveCatalogResourcesHandler(
    private val jdbi: Jdbi,
    private val planner: CatalogResourceMovePlanner,
    private val declarer: MissingDependencyDeclarer,
    relocators: List<ResourceRelocator>,
) : CommandHandler<MoveCatalogResources, CatalogResourceMovePreview> {
    /** Each type's relocator (ADR 0039): it moves and rewrites its type's resources, so this names no type. */
    private val relocators: Map<String, ResourceRelocator> = relocators.associateBy { it.type }

    private fun relocatorOf(type: String): ResourceRelocator = relocators[type] ?: error("No relocator for resource type $type")

    override fun handle(command: MoveCatalogResources): CatalogResourceMovePreview = jdbi.inTransaction<CatalogResourceMovePreview, Exception> { handle ->
        handle.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:tenantKey, 0))")
            .bind("tenantKey", command.tenantKey.value)
            .map { _, _ -> Unit }
            .one()

        val plan = planner.build(handle, command.tenantKey, command.relocations)
        if (plan.preview.planFingerprint != command.expectedPlanFingerprint) {
            throw StaleCatalogResourceMovePlanException()
        }
        if (!plan.preview.executable) throw CatalogResourceMoveBlockedException(plan.preview.blockers)

        // The expected-bytes guard makes every rewrite safe against a concurrent edit.
        for (rewrite in plan.rewrites) {
            if (relocatorOf(rewrite.ownerType).rewrite(handle, command.tenantKey, rewrite) != 1) throw StaleCatalogResourceMovePlanException()
        }

        for (relocation in planner.applyOrder(plan.preview.relocations)) {
            val resourceId = requireNotNull(relocation.resourceId)
            // What names it by alias and key follows it first, while it can still be found (a template its theme).
            relocators.values.forEach { it.beforeMove(handle, command.tenantKey, relocation, resourceId.value) }
            val moved = relocatorOf(relocation.source.type.wireName).move(
                handle,
                relocation.source.catalogIn(command.tenantKey),
                relocation.source.key,
                relocation.target.catalogIn(command.tenantKey),
                relocation.target.key,
            )
            if (!moved) throw StaleCatalogResourceMovePlanException()
        }

        // A reference that now crosses into another catalog makes its catalog depend on that one
        // (contract ADR 0003). The move implies it, so it is declared here rather than left for the
        // next release to refuse: on the draft of a catalog a resource moved into, since no release
        // of it holds the resource yet (ADR 0042).
        declarer.declareMissingInTenant(command.tenantKey)
        declarer.followMoved(
            command.tenantKey,
            command.relocations
                .filter { it.source.catalogAddress != it.target.catalogAddress }
                .groupBy({ it.target.catalogIn(command.tenantKey) }, { "${it.target.type.wireName}/${it.target.key}" })
                .mapValues { it.value.toSet() },
        )

        plan.preview
    }
}
