// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The domains depend on catalog, not the other way round: templates, themes, environments and the
 * rest name the catalog their content lives in (`CatalogId`, the version context), and catalog keeps
 * to its own ids, versions and storage. Where catalog must reach a domain, the domain implements a
 * catalog SPI that catalog collects, as environments does with
 * [app.epistola.suite.catalog.releases.CatalogDraftLifecycle].
 *
 * Checked against bytecode, like [CatalogExchangeIndependenceTest], so an inline fully qualified
 * reference counts too. Tenants is not in the list: a catalog belongs to a tenant, so catalog may
 * read it.
 *
 * [ALLOWED] is what is left to untangle, mostly the per-type import, export and relocation, which
 * still call each type's store. It may only shrink: the test fails as much for a dependency that is
 * gone and still listed as for a new one, so whoever removes one takes it off the list.
 */
class CatalogBoundaryTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("app.epistola.suite")

    @Test
    fun `catalog depends on no domain beyond what is still listed`() {
        assertEquals(ALLOWED, dependencies("catalog", DOMAINS), "catalog -> domain dependencies; a new one belongs behind an SPI the domain implements")
    }

    @Test
    fun `the mediator depends on catalog only where it targets a catalog version`() {
        assertEquals(MEDIATOR_ALLOWED, dependencies("mediator", listOf("catalog")), "mediator -> catalog dependencies")
    }

    /** Each class of [from] (by its top-level class) with a domain of [to] it depends on, as "class -> domain". */
    private fun dependencies(from: String, to: List<String>): Set<String> = classes
        .filter { it.packageName == "$ROOT.$from" || it.packageName.startsWith("$ROOT.$from.") }
        .flatMap { source ->
            source.directDependenciesFromSelf.mapNotNull { dependency ->
                val target = dependency.targetClass.packageName
                to.firstOrNull { target == "$ROOT.$it" || target.startsWith("$ROOT.$it.") }
                    ?.let { "${source.name.substringBefore('$').removePrefix("$ROOT.")} -> $it" }
            }
        }
        .toSortedSet()

    private companion object {
        const val ROOT = "app.epistola.suite"

        val DOMAINS = listOf("templates", "themes", "stencils", "assets", "attributes", "fonts", "environments", "documents", "generation")

        val ALLOWED = sortedSetOf(
            "catalog.CatalogUpgradeAnalyzer -> attributes",
            "catalog.commands.CatalogResourceInstaller -> assets",
            "catalog.commands.CatalogResourceInstaller -> attributes",
            "catalog.commands.CatalogResourceInstaller -> fonts",
            "catalog.commands.ImportAsset -> assets",
            "catalog.commands.ImportAssetHandler -> assets",
            "catalog.commands.ImportAttributeHandler -> attributes",
            "catalog.commands.ImportCodeList -> attributes",
            "catalog.commands.ImportCodeListHandler -> attributes",
            "catalog.commands.ImportStencilHandler -> stencils",
            "catalog.commands.ImportStencilHandler -> templates",
            "catalog.commands.MarkCatalogReadyHandler -> stencils",
            "catalog.commands.MarkCatalogReadyHandler -> templates",
            "catalog.commands.ReleaseCatalogDraftHandler -> attributes",
            "catalog.commands.UpdateCatalogMetadataHandler -> assets",
            "catalog.commands.UpdateCatalogMetadataHandler -> templates",
            "catalog.graph.TenantResourceGraphBuilder -> templates",
            "catalog.ProtocolMapper -> themes",
            "catalog.queries.ListCatalogsForManagementKt -> templates",
            "catalog.releases.ChangeKindEvaluator -> stencils",
            "catalog.releases.ChangeKindEvaluator -> templates",
            "catalog.ReleaseStencilCheck -> stencils",
            "catalog.relocation.CatalogResourceMovePlanner -> templates",
            "catalog.relocation.MoveCatalogResourcesHandler -> assets",
            "catalog.relocation.MoveCatalogResourcesHandler -> attributes",
            "catalog.relocation.MoveCatalogResourcesHandler -> fonts",
            "catalog.relocation.MoveCatalogResourcesHandler -> stencils",
            "catalog.relocation.MoveCatalogResourcesHandler -> templates",
            "catalog.relocation.MoveCatalogResourcesHandler -> themes",
        )

        /** Version targeting and the log context read the catalog version a message names. */
        val MEDIATOR_ALLOWED = sortedSetOf(
            "mediator.DispatchMdc -> catalog",
            "mediator.SpringMediator -> catalog",
        )
    }
}
