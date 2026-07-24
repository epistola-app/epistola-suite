// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup.schema

import app.epistola.suite.tenantbackup.BackupMigration
import org.jdbi.v3.core.Handle
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

/** Per-migration restore-compatibility flags. */
data class CompatibilityFlags(
    val backward: Boolean,
    val forward: Boolean,
)

/** The decision for one restore. */
sealed interface Compatibility {
    data object Compatible : Compatibility

    data class Incompatible(
        val reason: String,
    ) : Compatibility
}

/**
 * Decides whether a backup taken at schema `A` may be restored into the live schema `B`, from the
 * per-migration restore-compatibility flags declared as a **header in each migration `.sql` file**
 * (default deny — a migration with no header is incompatible in both directions). The flags live with
 * the migration that owns them, so a module ships them with its own migration files; the version is
 * taken from the filename (`V<version>__…`), so it cannot drift from the migration. Header form:
 *
 * ```sql
 * -- backup-restore-compatibility: backward=true forward=true
 * -- reason: <why crossing this migration is safe for a restore>
 * ```
 *
 * Two sources by direction:
 *  - **backward** (`B > A`): the live (newer) app reads the crossed migrations `(A, B]` from its own
 *    `flyway_schema_history` and this file.
 *  - **forward** (`B < A`): the live (older) app is blind to migrations newer than itself, so it reads
 *    the flags the backup snapshotted into its manifest (`appliedMigrations`).
 *
 * Stamps are the project's fixed-width `YYYYMMDDHHMMSS` migration versions, so lexicographic compare
 * equals chronological order. Restore also runs the structural `validateColumns` backstop regardless.
 */
@Component
class RestoreCompatibility {
    private val flagsByVersion: Map<String, CompatibilityFlags> = load()

    /** The declared flags for a migration version, or `false,false` if unlisted (default deny). */
    fun flagsFor(version: String): CompatibilityFlags = flagsByVersion[version] ?: CompatibilityFlags(backward = false, forward = false)

    /** All migration versions that declare a compatibility header. */
    fun declaredVersions(): Set<String> = flagsByVersion.keys

    /**
     * The greatest applied version `<= live` that is **not** declared backward-compatible — the
     * "backward boundary". A backup taken at stamp `A` (`A < live`) is backward-restorable iff
     * `boundary == null || A >= boundary` (no incompatible migration lies in the crossed range
     * `(A, live]`). Lets the Backups list classify every backward backup from **one** query instead of
     * calling [check] (and its per-backup query) for each. Equivalent to [check]'s backward branch.
     */
    fun backwardBoundary(
        handle: Handle,
        live: String,
    ): String? = handle
        .createQuery(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL AND version <= :live",
        ).bind("live", live)
        .mapTo(String::class.java)
        .list()
        .filterNot { flagsFor(it).backward }
        .maxOrNull()

    /**
     * Decides a restore. [backupStamp] is the manifest's `schemaStamp`; [appliedMigrations] is the
     * manifest's recorded flags (null on v1 backups → forward unavailable).
     */
    fun check(
        handle: Handle,
        backupStamp: String,
        appliedMigrations: List<BackupMigration>?,
    ): Compatibility {
        val live = SchemaStamp.current(handle)
        if (backupStamp == live) return Compatibility.Compatible

        return if (live > backupStamp) {
            // Backward: every migration applied after the backup must be backward-compatible (live file).
            val crossed = appliedVersionsBetween(handle, backupStamp, live)
            val bad = crossed.filterNot { flagsFor(it).backward }
            if (bad.isEmpty()) {
                Compatibility.Compatible
            } else {
                Compatibility.Incompatible(
                    "this backup is from an older schema and migration(s) ${bad.sorted()} are not declared backward-compatible for restore",
                )
            }
        } else {
            // Forward: the older live app reads the flags the backup recorded.
            if (appliedMigrations == null) {
                return Compatibility.Incompatible(
                    "this backup is from a newer schema and predates forward-restore support (no recorded migration flags)",
                )
            }
            val crossed = appliedMigrations.filter { it.version > live }
            val bad = crossed.filterNot { it.forward }
            if (bad.isEmpty()) {
                Compatibility.Compatible
            } else {
                Compatibility.Incompatible(
                    "this backup is from a newer schema and migration(s) ${bad.map { it.version }.sorted()} are not declared forward-compatible for restore",
                )
            }
        }
    }

    /** Applied migration versions in the half-open range `(after, upTo]`, from `flyway_schema_history`. */
    private fun appliedVersionsBetween(
        handle: Handle,
        after: String,
        upTo: String,
    ): List<String> = handle
        .createQuery(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL " +
                "AND version > :after AND version <= :upTo",
        ).bind("after", after)
        .bind("upTo", upTo)
        .mapTo(String::class.java)
        .list()

    /**
     * Scans every migration `.sql` on the classpath for a `backup-restore-compatibility` header and
     * maps the migration version (from the filename) to its flags. Migrations without the header are
     * absent → default deny. `classpath*:` spans every module's migrations, so each module's flags are
     * present exactly where its migration file is.
     */
    private fun load(): Map<String, CompatibilityFlags> {
        val resources = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/**/*.sql")
        val result = mutableMapOf<String, CompatibilityFlags>()
        for (resource in resources) {
            val version = resource.filename?.let { versionOf(it) } ?: continue
            val flags = resource.inputStream.use {
                try {
                    parseCompatibilityHeader(it.readBytes().decodeToString())
                } catch (e: IllegalArgumentException) {
                    throw IllegalStateException("Invalid backup-restore-compatibility header in ${resource.filename}: ${e.message}", e)
                }
            } ?: continue
            result[version] = flags
        }
        return result
    }

    companion object {
        /** `V20260622102813__core_audit_log.sql` → `20260622102813`; null for non-versioned (e.g. repeatable) migrations. */
        internal fun versionOf(filename: String): String? = VERSION_IN_FILENAME.find(filename)?.groupValues?.get(1)

        /**
         * Reads the compatibility flags from a migration's leading comment header. Returns null when no
         * header is present (→ default deny). **Throws** when a header line *is* present but doesn't
         * parse, so a typo (`backward = true`, a missing flag, …) fails loudly at startup instead of
         * silently defaulting to "incompatible" and blocking restores.
         */
        internal fun parseCompatibilityHeader(sql: String): CompatibilityFlags? {
            val directiveLine = sql.lineSequence()
                .takeWhile { it.isBlank() || it.trimStart().startsWith("--") }
                .firstOrNull { it.contains(DIRECTIVE_TOKEN) }
                ?: return null
            val match = COMPATIBILITY_HEADER.find(directiveLine)
                ?: throw IllegalArgumentException(
                    "\"${directiveLine.trim()}\" — expected `-- $DIRECTIVE_TOKEN: backward=<true|false> forward=<true|false>`",
                )
            return CompatibilityFlags(
                backward = match.groupValues[1] == "true",
                forward = match.groupValues[2] == "true",
            )
        }

        /** `V20260622102813__core_audit_log.sql` → `20260622102813`. */
        private val VERSION_IN_FILENAME = Regex("^V(\\d+)__")

        private const val DIRECTIVE_TOKEN = "backup-restore-compatibility"

        /** `-- backup-restore-compatibility: backward=true forward=false` */
        private val COMPATIBILITY_HEADER =
            Regex("$DIRECTIVE_TOKEN:\\s*backward=(true|false)\\s+forward=(true|false)")
    }
}
