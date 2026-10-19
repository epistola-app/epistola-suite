// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Before a catalog version is published, brings the catalogs it uses in step with what its preview
 * showed (docs/catalog-working-model.md, rule 7), in one transaction: all or nothing.
 *
 * - Each catalog in [publish] has its next version published — marked ready, numbered from its
 *   changes ([GetCatalogBorrowing]'s `nextNumber`) — because this version uses changes in it that were
 *   not published yet.
 * - Each catalog in [retie] has nothing to publish, but a newer published version than the one this
 *   version is tied to; it is tied to that one.
 *
 * Then every catalog used but not declared is declared ([DeclareUsedCatalogs]), and each declaration
 * of a catalog above is pinned to its new version.
 *
 * The publish of this version itself is the caller's next step; when it fails, what was published
 * here stays published, as any published version does.
 *
 * @return per catalog, the version this version is now tied to.
 */
data class PublishUsedCatalogs(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val publish: Set<String>,
    val retie: Set<String> = emptySet(),
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<Map<String, String>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_RELEASE
}

@Component
class PublishUsedCatalogsHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) : CommandHandler<PublishUsedCatalogs, Map<String, String>> {

    override fun handle(command: PublishUsedCatalogs): Map<String, String> {
        val borrowing = GetCatalogBorrowing(command.tenantKey, command.catalogKey, command.catalogVersion).query()
        fun borrowed(key: String) = borrowing.catalogs.firstOrNull { it.catalogKey == key }
            ?: throw ValidationException("catalogs", "${command.catalogKey.value} does not use catalog '$key'", ValidationCode.GENERIC)

        val tiedTo = linkedMapOf<String, CatalogDependencyRelease>()
        for (key in command.publish) {
            val number = borrowed(key).nextNumber
                ?: throw ValidationException("catalogs", "Catalog '$key' has nothing to publish", ValidationCode.GENERIC)
            val other = CatalogKey.of(key)
            MarkCatalogReady(command.tenantKey, other).execute()
            val released = ReleaseCatalogVersion(command.tenantKey, other, number).execute()
            tiedTo[key] = CatalogDependencyRelease(released.version, released.fingerprint)
        }

        DeclareUsedCatalogs(command.tenantKey, command.catalogKey, command.catalogVersion).execute()

        return jdbi.withHandle<Map<String, String>, Exception> { handle ->
            val declared = store.declarations(handle, command.tenantKey, command.catalogKey)
            for (key in command.retie) {
                val target = borrowed(key).retieTo ?: continue
                val declaration = declared.firstOrNull { it.catalogKey == key } ?: continue
                store.release(handle, command.tenantKey, declaration, target)?.let { tiedTo[key] = it }
            }
            val relocked = declared.map { declaration ->
                val target = tiedTo[declaration.catalogKey] ?: return@map declaration
                declaration.copy(releaseVersion = target.version, releaseFingerprint = target.fingerprint)
            }
            store.replace(handle, command.tenantKey, command.catalogKey, relocked)
            tiedTo.mapValues { it.value.version }
        }
    }
}
