// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreLocationPrefix
import org.flywaydb.core.api.Location
import org.slf4j.LoggerFactory
import org.springframework.boot.ExitCodeGenerator
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.PropertiesLoaderUtils
import java.sql.Connection

/**
 * The database holds a migration this build does not ship, and that migration is not recorded as
 * safe for code that predates it: the database was upgraded past this build by a change this build
 * cannot run against, so the process must not start. An [ExitCodeGenerator] for the same reason as
 * [app.epistola.suite.config.SchemaBehindException] — Spring Boot exits non-zero when it propagates.
 */
class SchemaAheadException(
    message: String,
) : IllegalStateException(message),
    ExitCodeGenerator {
    override fun getExitCode(): Int = 1
}

/** What the database recorded about one applied migration. */
data class RecordedDeclaration(
    val rollbackCompatible: Boolean,
    val recordedBy: String?,
)

/** An applied migration this build does not ship and cannot run against; [recorded] is null when nothing was recorded. */
data class BlockingMigration(
    val version: String,
    val recorded: RecordedDeclaration?,
)

/** How the applied schema relates to the migrations this build ships. */
sealed interface RollbackVerdict {
    /** This build ships every applied migration — the normal case, a schema that is behind included. */
    data object Shipped : RollbackVerdict

    /** Applied migrations this build does not ship, every one recorded as rollback-compatible. */
    data class AheadCompatible(
        val unshipped: List<String>,
    ) : RollbackVerdict

    /** At least one applied migration this build does not ship is not recorded as rollback-compatible. */
    data class AheadIncompatible(
        val unshipped: List<String>,
        val blocking: List<BlockingMigration>,
    ) : RollbackVerdict
}

/**
 * Whether code that predates a migration can still run against the schema it leaves — the property
 * that decides whether an installation can be rolled back past that migration.
 *
 * Each migration declares it in a header in its own `.sql` file, beside the
 * `backup-restore-compatibility` header where there is one:
 *
 * ```sql
 * -- rollback-compatible: true
 * -- reason: Adds a nullable column that code predating it never reads.
 * ```
 *
 * A migration without the header counts as `false` (default deny). The version comes from the
 * filename, so the declaration cannot drift from the migration it belongs to.
 */
object RollbackCompatibility {
    const val DIRECTIVE = "rollback-compatible"

    /**
     * Judges the applied migrations against the ones this build ships. Only migrations the build
     * does not ship matter: it runs correctly against every one it does, whatever they declare.
     */
    fun judge(
        applied: Collection<String>,
        shipped: Set<String>,
        recorded: Map<String, RecordedDeclaration>,
    ): RollbackVerdict {
        val unshipped = applied.filterNot { it in shipped }.distinct().sorted()
        if (unshipped.isEmpty()) return RollbackVerdict.Shipped
        val blocking = unshipped
            .filterNot { recorded[it]?.rollbackCompatible == true }
            .map { BlockingMigration(it, recorded[it]) }
        return if (blocking.isEmpty()) {
            RollbackVerdict.AheadCompatible(unshipped)
        } else {
            RollbackVerdict.AheadIncompatible(unshipped, blocking)
        }
    }

    /** `V20260622102813__core_audit_log.sql` → `20260622102813`; null for repeatable and non-migration files. */
    fun versionOf(filename: String): String? = VERSION_IN_FILENAME.find(filename)?.groupValues?.get(1)

    /**
     * Reads the declaration from a migration's leading comment block: true or false, or null when
     * the file has none. A directive line is one whose comment starts with the directive and a `:` or
     * `=`; prose that merely wraps onto a line starting with the word is not one. **Throws** when a
     * directive line does not parse, or appears twice, so a typo fails at startup instead of silently
     * counting as "not compatible".
     */
    fun parseHeader(sql: String): Boolean? {
        val directives = sql.lineSequence()
            .takeWhile { it.isBlank() || it.trimStart().startsWith("--") }
            .map { it.trimStart().removePrefix("--").trim() }
            .filter { DIRECTIVE_LINE.containsMatchIn(it) }
            .toList()
        if (directives.isEmpty()) return null
        require(directives.size == 1) { "declares `$DIRECTIVE` ${directives.size} times" }
        val match = HEADER.matchEntire(directives.single())
            ?: throw IllegalArgumentException(
                "\"-- ${directives.single()}\" — expected `-- $DIRECTIVE: <true|false>`",
            )
        return match.groupValues[1] == "true"
    }

    /**
     * Every versioned migration under [locations], mapped to its declaration (null when it has none).
     * These are the migrations this build ships: the same locations Flyway resolves from.
     */
    fun scan(
        locations: Array<Location>,
        classLoader: ClassLoader,
    ): Map<String, Boolean?> {
        val resolver = PathMatchingResourcePatternResolver(classLoader)
        val declarations = LinkedHashMap<String, Boolean?>()
        for (location in locations) {
            val pattern = when {
                CoreLocationPrefix.isClassPath(location) -> "classpath*:${location.rootPath}/**/*.sql"

                CoreLocationPrefix.isFileSystem(location) -> "file:${location.rootPath}/**/*.sql"

                else -> throw IllegalStateException(
                    "Cannot read rollback-compatibility declarations from Flyway location '$location': " +
                        "only classpath: and filesystem: locations are supported",
                )
            }
            for (resource in resolver.getResources(pattern)) {
                val filename = resource.filename ?: continue
                val version = versionOf(filename) ?: continue
                val sql = resource.inputStream.use { it.readBytes().decodeToString() }
                declarations[version] = try {
                    parseHeader(sql)
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("Invalid `$DIRECTIVE` header in $filename: ${e.message}", e)
                }
            }
        }
        return declarations
    }

    private val VERSION_IN_FILENAME = Regex("^V(\\d+)__")

    private val DIRECTIVE_LINE = Regex("^$DIRECTIVE\\s*[:=]")

    private val HEADER = Regex("$DIRECTIVE:\\s*(true|false)")
}

/**
 * Keeps a build off a schema it cannot run against, and leaves behind what a later, older build needs
 * to decide that. Runs inside [app.epistola.suite.config.FlywayConfig]'s migration strategy, so it
 * covers every mode — the app at boot, the dedicated migration step, and validate-only pods.
 *
 * - [requireCompatible] runs before Flyway touches anything. When the database holds migrations this
 *   build does not ship, each must be recorded as rollback-compatible, or it throws
 *   [SchemaAheadException] and the process exits non-zero.
 * - [recordApplied] runs after a migration step, successful or not. It records the declaration of
 *   every applied migration this build ships in `flyway_rollback_compatibility`, keyed by its
 *   `installed_rank` in `flyway_schema_history` and with its version, because an older build does
 *   not ship those files and can only read what was recorded. Rows are written once
 *   (`ON CONFLICT DO NOTHING`): a header is part of the migration's checksum, so it never changes.
 */
class SchemaRollbackGuard(
    private val flyway: Flyway,
    private val buildVersion: String? = BUILD_VERSION,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val declarations: Map<String, Boolean?> by lazy {
        RollbackCompatibility.scan(flyway.configuration.locations, flyway.configuration.classLoader)
    }

    fun requireCompatible() {
        val applied = appliedMigrations().map { it.version }
        when (val verdict = RollbackCompatibility.judge(applied, declarations.keys, recorded())) {
            RollbackVerdict.Shipped -> Unit

            is RollbackVerdict.AheadCompatible -> logger.warn(
                "Database schema is ahead of this build by {} migration(s) it does not ship ({}), each recorded " +
                    "as rollback-compatible, so this build starts. This is a rolled-back deployment.",
                verdict.unshipped.size,
                verdict.unshipped.joinToString(", "),
            )

            is RollbackVerdict.AheadIncompatible -> throw SchemaAheadException(aheadMessage(verdict))
        }
    }

    fun recordApplied() {
        val applied = appliedMigrations().filter { it.version in declarations }
        flyway.configuration.dataSource.connection.use { connection ->
            // Absent only when the step failed before the migration that creates it.
            if (!tableExists(connection)) return
            connection.prepareStatement(
                "INSERT INTO $TABLE (installed_rank, version, rollback_compatible, recorded_by) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (installed_rank) DO NOTHING",
            ).use { statement ->
                for (migration in applied) {
                    statement.setInt(1, migration.installedRank)
                    statement.setString(2, migration.version)
                    statement.setBoolean(3, declarations[migration.version] == true)
                    statement.setString(4, buildVersion)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        val floor = recorded().filterValues { !it.rollbackCompatible }.maxByOrNull { it.key }
        if (floor != null) {
            logger.info(
                "Rollback floor: a build must ship migration {} (recorded by {}), the newest applied migration " +
                    "that is not rollback-compatible, to start against this database",
                floor.key,
                floor.value.recordedBy ?: "an unversioned build",
            )
        }
    }

    private fun aheadMessage(verdict: RollbackVerdict.AheadIncompatible): String {
        val blocking = verdict.blocking.joinToString(", ") { migration ->
            val recorded = migration.recorded
            when {
                recorded == null -> "${migration.version} (no declaration recorded)"
                recorded.recordedBy == null -> "${migration.version} (not rollback-compatible)"
                else -> "${migration.version} (not rollback-compatible, recorded by ${recorded.recordedBy})"
            }
        }
        val floor = verdict.blocking.maxOf { it.version }
        val newestShipped = declarations.keys.maxOrNull() ?: "none"
        return "Database schema is ahead of this build, which cannot run against it. The database holds " +
            "migration(s) this build does not ship that are not rollback-compatible: $blocking. Code older than " +
            "those migrations cannot run on the schema they leave behind. Deploy a build that ships migration " +
            "$floor or later (this build ships up to $newestShipped), or restore the database backup taken " +
            "before the upgrade. See docs/upgrades.md#rollback."
    }

    /** Successfully applied versioned migrations, those this build does not ship included. */
    private fun appliedMigrations(): List<AppliedMigration> = flyway.info().applied()
        .filter { it.isVersioned && !it.type.isSynthetic && !it.state.isFailed }
        .map { AppliedMigration(it.installedRank, it.version.version) }

    /** One applied migration: its row in Flyway's history, and the version that row holds. */
    private data class AppliedMigration(
        val installedRank: Int,
        val version: String,
    )

    private fun recorded(): Map<String, RecordedDeclaration> = flyway.configuration.dataSource.connection.use { connection ->
        if (!tableExists(connection)) return emptyMap()
        connection.prepareStatement("SELECT version, rollback_compatible, recorded_by FROM $TABLE").use { statement ->
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(rows.getString(1), RecordedDeclaration(rows.getBoolean(2), rows.getString(3)))
                    }
                }
            }
        }
    }

    private fun tableExists(connection: Connection): Boolean = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { statement ->
        statement.setString(1, TABLE)
        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
    }

    companion object {
        const val TABLE = "flyway_rollback_compatibility"

        /**
         * The version from `META-INF/build-info.properties`, read directly rather than through
         * `BuildProperties`: the isolated migration context does not load project-info
         * auto-configuration, and it is the context that records most rows.
         */
        private val BUILD_VERSION: String? = runCatching {
            val resource = ClassPathResource("META-INF/build-info.properties")
            if (resource.exists()) PropertiesLoaderUtils.loadProperties(resource).getProperty("build.version") else null
        }.getOrNull()
    }
}
