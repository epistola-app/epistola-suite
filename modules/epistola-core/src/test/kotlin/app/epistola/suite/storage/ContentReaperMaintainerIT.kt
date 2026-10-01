// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * That the reaper actually drives its [ContentRetentionMaintainer]s, and that one failing does not
 * take the others with it.
 *
 * [FilesystemDocumentContentStoreTest] covers what the filesystem maintainer *does* by calling
 * `reclaim` directly. Nothing covered the wiring: whether `reap()` calls it at all, with the
 * configured retention. A regression there is silent — the sweep still reports blobs reclaimed and
 * the gauge still reads zero, while document files pile up on a filesystem-backed installation
 * until a disk fills.
 *
 * The reaper is constructed here rather than injected, because the point is to hand it maintainers
 * the application context does not have: PostgreSQL and S3 installations contribute none at all.
 */
class ContentReaperMaintainerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private class RecordingMaintainer(val failing: Boolean = false) : ContentRetentionMaintainer {
        val calledWith = mutableListOf<Int>()

        override fun reclaim(retentionMonths: Int) {
            calledWith.add(retentionMonths)
            if (failing) throw IllegalStateException("backend unavailable")
        }
    }

    private fun reaperWith(vararg maintainers: ContentRetentionMaintainer, retentionMonths: Int = 7) = ContentReaper(
        jdbi = jdbi,
        meterRegistry = SimpleMeterRegistry(),
        maintainers = maintainers.toList(),
        retentionMonths = retentionMonths,
        assetGraceMinutes = 60,
        reaperCron = "0 30 3 * * ?",
    )

    @Test
    fun `every maintainer is driven, with the configured retention`() {
        val first = RecordingMaintainer()
        val second = RecordingMaintainer()

        withMediator { reaperWith(first, second, retentionMonths = 7).reap() }

        assertThat(first.calledWith).containsExactly(7)
        assertThat(second.calledWith).containsExactly(7)
    }

    @Test
    fun `a maintainer that throws does not stop the others, and the run still finishes`() {
        val failing = RecordingMaintainer(failing = true)
        val after = RecordingMaintainer()

        // No assertThatThrownBy: the run is expected to complete. A backend being unreachable must
        // not cost the installation its other reclamation, nor the gauge that would show a leak.
        withMediator { reaperWith(failing, after).reap() }

        assertThat(failing.calledWith).`as`("the failing maintainer was reached").containsExactly(7)
        assertThat(after.calledWith).`as`("and the one after it still ran").containsExactly(7)
    }

    @Test
    fun `no maintainers at all is the normal case, not an error`() {
        // PostgreSQL reclaims through partition drops and S3 through a lifecycle rule, so both
        // contribute an empty list. The sweep and the gauge still have to run.
        withMediator { reaperWith().reap() }
    }
}
