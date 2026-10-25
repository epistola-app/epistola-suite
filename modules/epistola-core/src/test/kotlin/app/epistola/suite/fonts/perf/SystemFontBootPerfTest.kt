// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.perf

import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * What seeding the bundled system fonts costs at boot (ADR 0033, step 5): a tenant's first
 * seeding on a fresh installation (its own database, so nothing is stored yet, and the first call
 * in the JVM, so nothing is hashed yet), a second tenant's, and the pass every boot repeats for an
 * existing tenant (`InstallSystemCatalog`). Each tenant's creation seeds its system catalog, fonts
 * included.
 *
 * ```
 * ./gradlew :modules:epistola-core:perfTest --rerun --tests "*SystemFontBootPerfTest"
 * ```
 */
@Tag("perf")
@Tag("integration")
@TestPropertySource(properties = ["epistola.test.context=system-font-boot"])
class SystemFontBootPerfTest : IntegrationTestBase() {

    @Test
    fun `seeding the system fonts on a fresh installation and on every boot`() {
        fun timed(block: () -> Unit): Double {
            val start = System.nanoTime()
            block()
            return (System.nanoTime() - start) / 1e6
        }
        lateinit var first: String
        val firstTenant = timed { first = createTenant("Boot first").id.value }
        val secondTenant = timed { createTenant("Boot second") }
        val reseed = (1..20).map {
            timed { withMediator { InstallSystemCatalog(app.epistola.suite.common.ids.TenantKey.of(first)).execute() } }
        }.sorted()
        val seed = (1..20).map {
            timed { withMediator { app.epistola.suite.fonts.commands.EnsureSystemFonts(app.epistola.suite.common.ids.TenantKey.of(first)).execute() } }
        }.sorted()
        val report = buildString {
            appendLine("operation,ms")
            appendLine("create the first tenant (fresh installation),${"%.2f".format(firstTenant)}")
            appendLine("create a second tenant,${"%.2f".format(secondTenant)}")
            appendLine("of which: re-seed the system fonts (median of 20),${"%.2f".format(seed[seed.size / 2])}")
            appendLine("boot pass of an existing tenant's system catalog, fonts included (median of 20),${"%.2f".format(reseed[reseed.size / 2])}")
        }
        println("System font boot perf\n$report")
        val out = Path.of("build/perf-reports/system-font-boot.csv")
        Files.createDirectories(out.parent)
        Files.writeString(out, report)
    }
}
