// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.dependencies.CatalogBorrowing
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.queries.CatalogResourceChange
import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogRelease
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.releases.DraftNumberAssessment
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Where the next release of a catalog that can be changed here stands, and which of its actions to offer: the draft's
 * number, what changed since the last release and whether each change is ready, and the declared
 * dependencies the release is cut with. It is about the draft the page works in, [drafts]`.chosen`;
 * only the next version is published (contract ADR 0002, amendment 2), so parallel work shows what
 * changed in it but offers no publishing.
 *
 * Built for one catalog's page (it builds the working copy once, like [GetCatalogResourceChanges]);
 * the release dialog and the catalog page both gate on the names below, so the two cannot disagree.
 *
 * @property mayRelease the viewer holds `CATALOG_RELEASE`, which releasing a draft needs.
 * @property mayEditDraft the viewer holds `TEMPLATE_EDIT`, which editing or resolving a draft's
 *   dependencies needs (contract `updateCatalogDependencies`).
 * @property mayMarkReady the viewer holds `TEMPLATE_EDIT`, which marking resources ready needs.
 */
data class CatalogReleaseReadiness(
    val catalogKey: CatalogKey,
    /** The draft the page works in; null for a catalog with no open draft. */
    val draft: CatalogRelease?,
    /** Which draft this is, its siblings, and what may be done to them. */
    val drafts: CatalogDraftBar,
    val changes: CatalogResourceChanges,
    val dependencies: List<CatalogDependencyDeclaration>,
    val mayRelease: Boolean,
    val mayEditDraft: Boolean,
    val mayMarkReady: Boolean,
    /** The number the next version would be published as, and why; null for parallel work and without a draft. */
    val numbering: DraftNumberAssessment? = null,
    /** What the draft takes from other catalogs, and whether each is declared. */
    val borrowing: CatalogBorrowing = CatalogBorrowing(emptyList(), emptyList()),
) {
    /**
     * Catalogs used here but not declared, which releasing refuses: using a resource declares its
     * catalog, so only a catalog the tenant lacks, or one left undeclared by an upgrade, is here.
     */
    val blockingBorrowed: List<app.epistola.suite.catalog.dependencies.BorrowedCatalog> get() = borrowing.undeclared

    /** Catalogs whose drafts this one builds on and publishing publishes first. */
    val publishedAlong: List<app.epistola.suite.catalog.dependencies.BorrowedCatalog> get() = borrowing.publishedAlong

    /** There is a draft to show changes for and declare dependencies in. */
    val hasDraft: Boolean get() = draft != null

    /** There is a draft to publish: any draft is released on its own (contract ADR 0002). */
    val publishable: Boolean get() = draft != null

    /** The changed resources not marked ready; releasing refuses while there is one. */
    val notReady: List<CatalogResourceChange> get() = changes.notReady

    /** Every resource that differs from the last release, in manifest order. */
    val changed: List<CatalogResourceChange> get() = changes.resources.filter { it.state != CatalogResourceState.RELEASED }

    /**
     * The declarations that pin no release and name no draft, which releasing refuses
     * (`CATALOG_DEPENDENCY_UNRESOLVED`). A pin that stopped holding is caught by the release itself;
     * a declaration naming a draft is released along with it, when the author asks.
     */
    val unresolvedDependencies: List<CatalogDependencyDeclaration> get() = dependencies.filter { !it.pinned && !it.namesDraft }

    /**
     * Whether to offer Release: the viewer may release, and nothing blocks it. When the viewer may
     * but something blocks it, [releaseBlocked] says so and the page names what, instead of offering
     * a release that would be refused.
     */
    val offerRelease: Boolean get() = mayRelease && publishable && notReady.isEmpty() && unresolvedDependencies.isEmpty()

    /** The viewer may release, but a resource is not ready or a dependency is unresolved. */
    val releaseBlocked: Boolean get() = mayRelease && publishable && !offerRelease

    // ---- Publish, as people see it (docs/catalog-workspace-navigation.md, "Simple model") ----

    /**
     * Nothing differs from the last published version, so there is nothing to publish. A catalog
     * never published always has something: its first version.
     */
    val nothingToPublish: Boolean
        get() = hasDraft && drafts.releases.isNotEmpty() && !changes.hasUnreleasedChanges && publishedAlong.isEmpty()

    /**
     * Nothing in this catalog itself changed, but a catalog it uses did, in a way publishing would
     * take over (published along): publishing is how that change reaches what is live.
     */
    val onlyOthersChanged: Boolean get() = !nothingToPublish && changed.isEmpty() && publishedAlong.isNotEmpty()

    /**
     * The number this is published as, picked for the person from what changed since the latest
     * release (fixes a patch, additions a minor, breaking changes a major): a draft has no number until
     * it is published.
     */
    val publishVersion: String? get() = numbering?.suggested?.takeIf { publishable }

    /** What the changes mean for whatever is connected to the catalog; null when there is nothing to compare with. */
    val impact: ChangeKind? get() = numbering?.needs

    /**
     * Why the impact is what it is, breaking first, with each resource named as people know it:
     * `Code list Languages: entry 'nl' removed` rather than the evaluator's `codeList 'languages': …`.
     */
    val impactReasons: List<String>
        get() = numbering?.reasons.orEmpty().map { reason ->
            val match = REASON.matchEntire(reason) ?: return@map reason
            val (type, key, text) = match.destructured
            val name = changes.resources.firstOrNull { it.type == type && it.slug == key }?.name ?: key
            "${typeLabel(type)} $name: $text"
        }

    /** Ready marks are set as part of publishing: the viewer may set them, so they are no reason to stop. */
    val marksReadyOnPublish: Boolean get() = notReady.isNotEmpty() && mayMarkReady

    /**
     * Whether to offer Publish: the viewer may, there is something to publish, and nothing blocks it
     * that publishing cannot do itself. Unready changes do not block someone who may mark them.
     */
    val offerPublish: Boolean
        get() = mayRelease &&
            publishable &&
            !nothingToPublish &&
            unresolvedDependencies.isEmpty() &&
            (notReady.isEmpty() || mayMarkReady) &&
            blockingBorrowed.isEmpty()

    /** The viewer may publish and there is something to, but a dependency (or an unready change they may not mark) stops it. */
    val publishBlocked: Boolean get() = mayRelease && publishable && !nothingToPublish && !offerPublish

    /** The changed resources, with a readable kind, for the "what changes" list. */
    val changedForPeople: List<PublishedChange>
        get() = changed.map { PublishedChange(typeLabel(it.type), it.name ?: it.slug, it.state) }

    /** A resource type as people say it, for templates. */
    fun labelOf(type: String): String = typeLabel(type)

    /** One line of "what changes": the kind of resource in words, its name, and how it changed. */
    data class PublishedChange(val kind: String, val name: String, val state: CatalogResourceState)

    /** Whether to offer "Mark all ready": the viewer may mark, and a changed resource is not ready. */
    val offerMarkAllReady: Boolean get() = mayMarkReady && hasDraft && notReady.isNotEmpty()

    /** Whether to offer "Resolve to latest": the viewer may, and there is a declaration to move. */
    val offerUpgradeDependencies: Boolean get() = mayEditDraft && hasDraft && dependencies.isNotEmpty()

    /** Whether to offer editing the declared dependencies: the viewer may, and there is a draft to declare them in. */
    val offerEditDependencies: Boolean get() = mayEditDraft && hasDraft

    /**
     * Whether to offer merging a published version this draft is behind into it (ADR 0032, alpha):
     * the viewer may edit the draft, and there is a version it lacks. Merging changes the draft's
     * content, which `MergeReleaseIntoDraft` gates on `TEMPLATE_EDIT`.
     */
    val offerMerge: Boolean get() = mayEditDraft && hasDraft && drafts.behind.isNotEmpty()

    /** The draft is behind a published version, but the viewer may not merge it in: the page says why. */
    val mergeWithheld: Boolean get() = !mayEditDraft && hasDraft && drafts.behind.isNotEmpty()

    companion object {
        /** `type 'key': reason`, as `ChangeReason` words it. */
        private val REASON = Regex("""^(\S+) '([^']+)': (.+)$""")

        /** A resource type as people say it. */
        fun typeLabel(type: String): String = when (type) {
            "template" -> "Template"
            "theme" -> "Theme"
            "stencil" -> "Stencil"
            "codeList" -> "Code list"
            "attribute" -> "Attribute"
            "font" -> "Font"
            "image" -> "Image"
            "catalog" -> "Catalog setting"
            else -> type.replaceFirstChar { it.uppercase() }
        }

        /** Reads the readiness of the draft [drafts] works in, for the current principal. */
        fun of(drafts: CatalogDraftBar): CatalogReleaseReadiness {
            val tenantKey = drafts.tenantKey
            val catalogKey = drafts.catalogKey
            val auth = AuthContext.from(SecurityContext.current(), tenantKey)
            val draft = drafts.chosen
            return CatalogReleaseReadiness(
                catalogKey = catalogKey,
                draft = draft,
                drafts = drafts,
                // Without a draft there is nothing that changed, and nothing to declare dependencies in.
                changes = if (draft == null) {
                    CatalogResourceChanges(catalogKey, drafts.releases.firstOrNull()?.version, resources = emptyList())
                } else {
                    GetCatalogResourceChanges(tenantKey, catalogKey, catalogVersion = drafts.ref).query()
                },
                dependencies = if (draft == null) emptyList() else ListCatalogDependencies(tenantKey, catalogKey, catalogVersion = drafts.ref).query(),
                mayRelease = auth.has(Permission.CATALOG_RELEASE),
                mayEditDraft = auth.has(Permission.TEMPLATE_EDIT),
                mayMarkReady = auth.has(Permission.TEMPLATE_EDIT),
                // The number this draft needs against its own base: a fix to an older line is numbered on that line.
                numbering = draft?.draftRef?.let { AssessDraftNumber(tenantKey, catalogKey, it).query() },
                borrowing = if (draft == null) CatalogBorrowing(emptyList(), emptyList()) else GetCatalogBorrowing(tenantKey, catalogKey, catalogVersion = drafts.ref).query(),
            )
        }
    }
}
