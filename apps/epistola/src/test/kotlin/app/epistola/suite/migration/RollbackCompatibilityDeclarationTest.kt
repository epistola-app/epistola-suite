// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.api.locations.LocationParser
import org.junit.jupiter.api.Test

/**
 * Every migration from the one that introduced rollback compatibility onward says whether code that
 * predates it can run against the schema it leaves. A missing header still counts as "not
 * compatible" at runtime, but that is a silent default; this makes it a decision the author takes.
 *
 * Migrations before [FIRST_DECLARING] cannot gain a header: it is part of their checksum, and they
 * are applied on every installation. They count as not compatible, which costs nothing, because every
 * build that carries the check ships all of them.
 *
 * Runs in `apps:epistola`, whose classpath carries every module's migrations, as production does.
 */
class RollbackCompatibilityDeclarationTest {

    private val declarations =
        RollbackCompatibility.scan(arrayOf(LocationParser.parseLocation("classpath:db/migration")), javaClass.classLoader)

    @Test
    fun `every migration since rollback compatibility was introduced declares it`() {
        assertThat(declarations).containsKey(FIRST_DECLARING)
        val undeclared = declarations.filter { (version, declared) -> version >= FIRST_DECLARING && declared == null }.keys
        assertThat(undeclared)
            .describedAs(
                "Migrations without a `-- rollback-compatible: true|false` header. Add one to each — see " +
                    "docs/migrations.md#rollback-compatibility for which to choose.",
            )
            .isEmpty()
    }

    @Test
    fun `every migration version is a fixed-width timestamp, so string order is apply order`() {
        // The guard compares versions as strings when it names the oldest build a database accepts.
        assertThat(declarations.keys).allSatisfy { assertThat(it).matches("\\d{14}") }
    }

    companion object {
        /** `V20261022152421__core_schema_rollback_compatibility.sql`, which created the table the check reads. */
        const val FIRST_DECLARING = "20261022152421"
    }
}
