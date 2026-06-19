package app.epistola.suite.tenantbackup.schema

import app.epistola.suite.tenantbackup.BackupMigration
import org.jdbi.v3.core.Handle
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml

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
 * per-migration flags in `schema-backup-compatibility.yaml` (default deny). See the file's header for
 * the direction model. Two sources by direction:
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

    /** All versions the file declares (for the existence-validation test). */
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

    @Suppress("UNCHECKED_CAST")
    private fun load(): Map<String, CompatibilityFlags> {
        val resource = ClassPathResource(RESOURCE)
        val root = resource.inputStream.use { Yaml().load<Map<String, Any?>>(it) } ?: return emptyMap()
        val migrations = (root["migrations"] as? List<Map<String, Any?>>).orEmpty()
        return migrations.associate { row ->
            val version = row["version"]?.toString() ?: error("$RESOURCE entry is missing 'version'")
            version to CompatibilityFlags(backward = row["backward"] == true, forward = row["forward"] == true)
        }
    }

    private companion object {
        const val RESOURCE = "schema-backup-compatibility.yaml"
    }
}
