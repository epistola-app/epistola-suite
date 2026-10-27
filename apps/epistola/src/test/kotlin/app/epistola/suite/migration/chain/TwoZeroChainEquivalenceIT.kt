// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.chain

import app.epistola.suite.migration.DataPreservationMigrationIT
import app.epistola.suite.migration.MigrationLauncher
import app.epistola.suite.migration.OneXUpgradeScenariosIT
import app.epistola.suite.migration.preflight.UpgradePreflights
import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 2.0 migration chain converts a 1.x database exactly as the chain it replaced did.
 *
 * The 2.0 chain was written again from scratch, from the last 1.x schema straight to the 2.0 one. What
 * the chain before it left — its schema and every row it made of a rich 1.x seed — is kept as a golden
 * snapshot (`migration-chain/`), recorded from that chain. This migrates the same seed through the
 * chain on the classpath and holds it to the snapshot: the schema statement for statement, the data
 * row for row (generated ids and the time of the run replaced by labels, [ChainSnapshot]). Deliberate
 * differences, such as the indexes the rewrite adds, are listed in `migration-chain/intended.txt`.
 *
 * The seed is the RC1 tenant of [DataPreservationMigrationIT], migrated through 1.x, plus the 1.x
 * scenarios of [OneXUpgradeScenariosIT]. To record the snapshot again from a chain (only ever the old
 * one, from history), set `EPISTOLA_MIGRATION_CHAIN_RECORD` to this module's `src/test/resources`.
 */
@Tag("integration")
class TwoZeroChainEquivalenceIT {

    @Test
    fun `the 2_0 chain converts a 1_x database as the chain it replaced did`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "two_zero_chain_equivalence"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        migrate(url, postgres.username, postgres.password, "spring.flyway.target=${DataPreservationMigrationIT.RC1_LAST_MIGRATION}")
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(DataPreservationMigrationIT.RC1_SEED) } }
        migrate(
            url,
            postgres.username,
            postgres.password,
            "spring.flyway.target=$LAST_1X",
            "epistola.migration.preflight.skip=${UpgradePreflights.checks.joinToString(",") { it.id }}",
        )
        connect(url, postgres.username, postgres.password).use { c -> c.createStatement().use { it.execute(OneXUpgradeScenariosIT.seed(PARTNER_SOURCE)) } }
        migrate(url, postgres.username, postgres.password)

        val schema = ChainSnapshot.schema(postgres, database)
        val data = connect(url, postgres.username, postgres.password).use { ChainSnapshot.data(it) }
        val ambiguous = connect(url, postgres.username, postgres.password).use { ChainSnapshot.ambiguousRows(it) }

        System.getenv("EPISTOLA_MIGRATION_CHAIN_RECORD")?.let { resources ->
            val dir = Path.of(resources, "migration-chain")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("schema.sql"), schema.joinToString(";\n\n", postfix = ";\n"))
            Files.writeString(dir.resolve("data.txt"), data.joinToString("\n", postfix = "\n"))
            return
        }

        val intended = Intended.load()
        val goldenSchema = ChainSnapshot.statements(resource("schema.sql")).map { it.trim() }.filter { it.isNotEmpty() }.map(ChainSnapshot::monthless).sorted()
        assertThat(schema.toSet() - goldenSchema.toSet()).`as`("statements the chain adds beyond the old one's schema").containsExactlyInAnyOrderElementsOf(intended.added)
        assertThat(goldenSchema.toSet() - schema.toSet()).`as`("statements of the old chain's schema this chain does not make").containsExactlyInAnyOrderElementsOf(intended.removed)

        val goldenData = resource("data.txt").lines().filter { it.isNotEmpty() }.map(intended::withoutDroppedColumns)
        assertThat(data.map(intended::withoutDroppedColumns)).`as`("every row the old chain made of the seed, and nothing else").containsExactlyElementsOf(goldenData)
        assertThat(ambiguous).`as`("rows the snapshot cannot tell apart, whose labels could swap between runs").isEmpty()
    }

    private fun migrate(url: String, username: String, password: String, vararg props: String) {
        MigrationLauncher.migrationApplication()
            .run(
                "--spring.datasource.url=$url",
                "--spring.datasource.username=$username",
                "--spring.datasource.password=$password",
                "--epistola.migration.mode=migrate",
                "--spring.flyway.clean-disabled=true",
                *props.map { "--$it" }.toTypedArray(),
            )
            .close()
    }

    private fun connect(url: String, username: String, password: String): Connection = DriverManager.getConnection(url, username, password)

    private fun resource(name: String): String = requireNotNull(javaClass.getResource("/migration-chain/$name")) { "missing migration-chain/$name" }.readText()

    /**
     * The deliberate differences from the old chain: `+ statement` it adds, `- statement` it no longer
     * makes, `column table.column` a column whose values are no longer compared because it is gone.
     */
    private class Intended(val added: List<String>, val removed: List<String>, private val droppedColumns: Set<String>) {
        fun withoutDroppedColumns(line: String): String {
            val table = line.substringBefore(": ")
            val dropped = droppedColumns.filter { it.startsWith("$table.") }.map { it.substringAfter('.') }
            if (dropped.isEmpty()) return line
            return dropped.fold(line) { acc, column -> acc.replace(Regex(""""$column":(?:"(?:[^"\\]|\\.)*"|[^,}]*),?"""), "") }.replace(",}", "}")
        }

        companion object {
            fun load(): Intended {
                val text = TwoZeroChainEquivalenceIT::class.java.getResource("/migration-chain/intended.txt")?.readText().orEmpty()
                val entries = text.split(Regex("\n(?=[+\\-] |column )")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                return Intended(
                    added = entries.filter { it.startsWith("+ ") }.map { it.removePrefix("+ ").trim().removeSuffix(";") },
                    removed = entries.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim().removeSuffix(";") },
                    droppedColumns = entries.filter { it.startsWith("column ") }.map { it.removePrefix("column ").trim() }.toSet(),
                )
            }
        }
    }

    private companion object {
        const val LAST_1X = "20260923150918"

        /** The source a 1.x tenant subscribed to; never fetched here, only stored by the seed. */
        const val PARTNER_SOURCE = "file:///nonexistent/partner/catalog.json"
    }
}
