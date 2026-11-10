// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing in the application switches on with the `test` profile. A bean only for tests belongs in
 * test sources or the test-only library (`modules/testing`): in the application it waits for a
 * deployment that activates `test` by mistake, and the permit-all security chain that used to sit
 * in `SecurityConfig` would then have opened every route. `@Profile("!test")`, which turns
 * something off in tests, is fine.
 */
class NoTestProfileInProductionTest {
    private val testOnly = Regex("""@Profile\(\s*(?:value\s*=\s*)?(?:\[\s*)?"test"""")

    @Test
    fun `no production bean is active only under the test profile`() {
        val files = RepoSources.mainKotlinFiles().filterNot { "modules/testing/" in RepoSources.relativize(it) }
        assertTrue(files.size > 500, "only ${files.size} main sources found — the scan would pass vacuously")

        val offenders = files.flatMap { file ->
            val source = RepoSources.stripComments(Files.readString(file))
            testOnly.findAll(source).map { match ->
                "${RepoSources.relativize(file)}:${source.substring(0, match.range.first).count { it == '\n' } + 1}"
            }.toList()
        }

        assertEquals(emptyList(), offenders, "Move test-only beans to test sources or modules/testing")
    }
}
