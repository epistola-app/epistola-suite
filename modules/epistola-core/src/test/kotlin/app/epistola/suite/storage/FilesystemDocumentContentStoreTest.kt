// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The filesystem document backend's age sweep (#738): document files older than the
 * retention window are deleted (with their sidecars); anything outside `documents/`
 * — e.g. asset files — is never touched.
 */
class FilesystemDocumentContentStoreTest {

    @Test
    fun `reclaim sweeps aged document files and leaves everything else`(@TempDir base: Path) {
        val store = FilesystemDocumentContentStore(base)
        val now = OffsetDateTime.of(2026, 6, 10, 0, 0, 0, 0, ZoneOffset.UTC)

        // A recent document, an old document, and a non-document file that must survive.
        store.put("documents/t/fresh", ByteArrayInputStream(byteArrayOf(1)), "application/pdf", 1, now.minusDays(1))
        store.put("documents/t/old", ByteArrayInputStream(byteArrayOf(2)), "application/pdf", 1, now.minusMonths(6))
        val assetFile = base.resolve("assets/t/keep.png")
        Files.createDirectories(assetFile.parent)
        Files.write(assetFile, byteArrayOf(9))

        EpistolaClock.withInstant(now.toInstant()) {
            store.reclaim(retentionMonths = 3)
        }

        assertThat(store.exists("documents/t/fresh")).`as`("recent doc kept").isTrue
        assertThat(store.exists("documents/t/old")).`as`("aged doc swept").isFalse
        assertThat(Files.exists(base.resolve("documents/t/old.meta"))).`as`("sidecar swept").isFalse
        assertThat(Files.exists(assetFile)).`as`("non-document file untouched").isTrue
    }

    @Test
    fun `reclaim is a no-op when no documents directory exists`(@TempDir base: Path) {
        val store = FilesystemDocumentContentStore(base)
        EpistolaClock.withInstant(Instant.parse("2026-06-10T00:00:00Z")) {
            store.reclaim(retentionMonths = 3)
        }
        // Nothing to assert beyond "did not throw".
    }
}
