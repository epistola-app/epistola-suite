// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme

/** The archives of [parallelReleaseLines], each a release as its producer exported it. */
class ParallelReleaseLines(
    val catalogKey: CatalogKey,
    /** Themes `brand` and `letterhead`. */
    val v100: ByteArray,
    /** Made from 1.0.0: adds theme `added`. */
    val v110: ByteArray,
    /** Made from 1.0.0 in parallel work: renames `brand` to "Brand, fixed". */
    val v101: ByteArray,
)

/**
 * Two lines of releases of one catalog, made in [producer] (another installation, played by a
 * tenant): 1.0.0, then 1.1.0 from the next release, and 1.0.1 from parallel work on 1.0.0. Every
 * archive names the release it was made from (`release.base`), so a receiver can follow the lines
 * to 1.0.0, their merge base (contract ADR 0005).
 */
fun Mediator.parallelReleaseLines(producer: TenantKey, catalogKey: CatalogKey): ParallelReleaseLines {
    fun theme(key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalogKey, TenantId(producer)))
    send(CreateCatalog(tenantKey = producer, id = catalogKey, name = "Lines ${catalogKey.value}"))
    send(CreateTheme(theme("brand"), "Brand"))
    send(CreateTheme(theme("letterhead"), "Letterhead"))
    send(MarkCatalogReady(producer, catalogKey))
    send(ReleaseCatalogDraft(tenantKey = producer, catalogKey = catalogKey, version = "1.0.0"))

    send(CreateTheme(theme("added"), "Added"))
    send(MarkCatalogReady(producer, catalogKey))
    send(ReleaseCatalogDraft(tenantKey = producer, catalogKey = catalogKey, version = "1.1.0"))

    val fix = send(CreateCatalogDraft(producer, catalogKey, "Fix", "1.0.0"))
    val fixRef = CatalogReleaseRef(producer, catalogKey, DraftRef.Id(fix.versionId))
    send(UpdateTheme(theme("brand"), name = "Brand, fixed", catalogVersion = fixRef))
    send(MarkCatalogReady(producer, catalogKey, catalogVersion = fixRef))
    send(ReleaseCatalogDraft(tenantKey = producer, catalogKey = catalogKey, version = "1.0.1", catalogVersion = fixRef))

    fun archive(version: String) = send(ExportCatalogZip(producer, catalogKey, version)).zipBytes
    return ParallelReleaseLines(catalogKey, archive("1.0.0"), archive("1.1.0"), archive("1.0.1"))
}
