// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a release arrived (`catalog_versions.released_via`, `ReleaseChannel`) is provenance: recorded
 * where a release arrives and shown where it is described, never decided by. A release is a release
 * however it arrived (ADR 0036), so the code that names it is these files, and a new one is a reason
 * to ask whether it describes the release or branches on how it came.
 */
class ReleaseProvenanceUsageTest {
    private val provenance = Regex("""\b(ReleaseChannel|ReleaseProvenance|releasedVia|released_via)\b""")

    /** The files that record provenance (where a release arrives) or show it (where it is described). */
    private val allowed = setOf(
        // The type itself.
        "ReleaseChannel.kt",
        // Recording: the install core, the release record, and every way a release arrives.
        "ImportCatalogZip.kt",
        "RecordInstalledRelease.kt",
        "CatalogReleases.kt",
        "CatalogReleaseSources.kt",
        "InstallFromCatalog.kt",
        "UpgradeCatalog.kt",
        "ReceiveOneXSubscribedReleases.kt",
        "RegistryCatalogReleaseSource.kt",
        "InstallReleaseFromRegistry.kt",
        "ExchangeCatalogInstaller.kt",
        "CatalogImportHandler.kt",
        "EpistolaCatalogApi.kt",
        // Carried through a tenant snapshot as it was.
        "TenantSnapshot.kt",
        "BuildTenantSnapshot.kt",
        "RestoreTenantSnapshot.kt",
        // Shown: the release listing, the release page, MCP's release list.
        "ListCatalogReleases.kt",
        "GetCatalogReleaseDetail.kt",
        "CatalogReleaseInfo.kt",
    )

    @Test
    fun `only the code that records or shows a release's provenance names it`() {
        val files = RepoSources.mainKotlinFiles()
        assertTrue(files.any { it.name == "ReleaseChannel.kt" }, "ReleaseChannel.kt not found — the scan would pass vacuously")

        val offenders = files
            .filter { it.name !in allowed }
            .filter { provenance.containsMatchIn(RepoSources.stripComments(Files.readString(it))) }
            .map(RepoSources::relativize)

        assertEquals(emptyList(), offenders, "How a release arrived is shown, never decided by (ADR 0036): record or show it, do not branch on it")
    }
}
