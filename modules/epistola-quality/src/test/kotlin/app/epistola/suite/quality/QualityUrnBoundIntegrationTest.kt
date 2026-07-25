// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * `subject_urn` and `ignore_scope_urn` are bounded at 512 by **arithmetic, not guesswork**: a URN is
 * `urn:epistola:<type>:` plus length-capped domain slugs, so the longest one possible today is a
 * version URN at 248 and the longest foreseeable a Phase 5 render subject at 284.
 *
 * That arithmetic is only true while the domains it rests on are. Widening `TENANT_KEY`, or adding a
 * subject type with a far longer URN, would erode the headroom silently — and the first anyone would
 * hear of it is a "value too long" on somebody's submit, from a source that had done nothing wrong.
 * This asserts the sums against the live schema so that surfaces here instead.
 */
class QualityUrnBoundIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var jdbi: Jdbi

    private fun urnColumnLimit(
        table: String,
        column: String,
    ): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT character_maximum_length FROM information_schema.columns
            WHERE table_name = :table AND column_name = :column
            """,
        )
            .bind("table", table)
            .bind("column", column)
            .mapTo(Int::class.java)
            .one()
    }

    /** The slug domains a subject URN is built from, straight out of the live schema. */
    private fun slugBudget(): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COALESCE(SUM(character_maximum_length), 0)::int
            FROM information_schema.domains
            WHERE domain_name IN ('tenant_key', 'catalog_key', 'template_key', 'variant_key')
            """,
        ).mapTo(Int::class.java).one()
    }

    @Test
    fun `the URN bound keeps real headroom over the longest URN the domains permit`() {
        val budget = slugBudget()
        assertThat(budget)
            .describedAs("all four slug domains must be found, or the arithmetic below means nothing")
            .isGreaterThan(200)

        // urn:epistola:<longest type>: + every slug at max + separators + a version number.
        val longestPossible = "urn:epistola:contract-version:".length + budget + 4 + 10

        assertThat(urnColumnLimit("quality_findings", "subject_urn"))
            .describedAs("subject_urn must stay clear of the longest URN its domains permit")
            .isGreaterThan(longestPossible)
        assertThat(urnColumnLimit("quality_findings", "ignore_scope_urn"))
            .isGreaterThan(longestPossible)
    }

    /** The ignore holds the same value, and it is in that table's primary key. */
    @Test
    fun `both tables bound the ignore scope identically`() {
        assertThat(urnColumnLimit("quality_finding_ignores", "ignore_scope_urn"))
            .isEqualTo(urnColumnLimit("quality_findings", "ignore_scope_urn"))
    }
}
