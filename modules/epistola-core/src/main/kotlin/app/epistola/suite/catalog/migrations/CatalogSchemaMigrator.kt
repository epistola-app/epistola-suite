package app.epistola.suite.catalog.migrations

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION
import app.epistola.suite.catalog.CATALOG_MANIFEST_SCHEMA_VERSION
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Upgrades an imported catalog payload from the wire schema version it was
 * exported at to [CATALOG_MANIFEST_SCHEMA_VERSION], by running an ordered chain
 * of [CatalogSchemaMigration] steps over the **JSON tree before binding**, then
 * deserializing the current-shape tree into the typed protocol model.
 *
 * This is the single migration chokepoint for both transports: the ZIP path
 * (`ImportCatalogZipHandler`, which holds `catalog.json` bytes directly) and the
 * remote/classpath path (`CatalogClient.fetchManifest`, shared by every remote
 * consumer — install, browse, upgrade-check, fingerprint). Putting the gate in
 * `CatalogClient` rather than only the install command means read-only query
 * paths see migrated content too.
 *
 * **Version gate** (read from the manifest tree, the authoritative catalog
 * version — resource-detail `schemaVersion` stamps have historically drifted and
 * are not trusted):
 *
 * - `== current` — bind directly (fast path; today's behaviour).
 * - `> current` — [CatalogSchemaTooNewException].
 * - `< current` with a migration path — run the chain, then bind.
 * - `< current`, no migrations defined at all — bind as-is. **Transitional:**
 *   no chain exists yet and pre-versioning stamps are unreliable, so a
 *   sub-current payload is assumed already current-shape (which is how every
 *   such payload imports today). The first real migration replaces this with
 *   strict baseline enforcement.
 * - `< baseline` (only reachable once migrations exist) —
 *   [CatalogSchemaTooOldException].
 * - missing / non-integer `schemaVersion` — [CatalogSchemaUnknownException].
 *
 * The chain is validated **contiguous and total** at startup (see
 * [validateMigrationChain]); a malformed chain fails application start, the same
 * fail-fast posture as a broken Flyway sequence.
 *
 * See `docs/adr/0006-catalog-wire-format-migrations.md`.
 */
@Component
class CatalogSchemaMigrator(
    private val objectMapper: ObjectMapper,
    migrations: List<CatalogSchemaMigration>,
    private val current: Int,
    private val baseline: Int,
) {
    /**
     * Production constructor — Spring injects the registered steps and the
     * version window is read from [CatalogConstants]. Tests use the primary
     * constructor with an explicit window to exercise a non-empty chain.
     */
    @Autowired
    constructor(objectMapper: ObjectMapper, migrations: List<CatalogSchemaMigration>) :
        this(objectMapper, migrations, CATALOG_MANIFEST_SCHEMA_VERSION, CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION)

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Steps keyed by `from`. Empty until the first real migration. */
    private val byFrom: Map<Int, CatalogSchemaMigration>

    init {
        val sorted = migrations.sortedBy { it.from }
        validateMigrationChain(sorted, baseline, current)
        byFrom = sorted.associateBy { it.from }
        if (sorted.isEmpty()) {
            logger.info("Catalog wire-format migration chain is empty (current schema v{}).", current)
        } else {
            logger.info(
                "Catalog wire-format migration chain: v{} → v{} ({} step(s)).",
                baseline,
                current,
                sorted.size,
            )
        }
    }

    /**
     * Parse [rawManifest], gate/upgrade it to the current schema version, and
     * bind it to [CatalogManifest]. Throws a [CatalogSchemaException] if the
     * version is unreadable, too new, or too old.
     */
    fun migrateAndBindManifest(rawManifest: ByteArray): CatalogManifest {
        val tree = objectMapper.readTree(rawManifest) as? ObjectNode
            ?: throw CatalogSchemaUnknownException("manifest is not a JSON object")
        val migrated = migrateManifestTree(tree)
        return objectMapper.treeToValue(migrated, CatalogManifest::class.java)
    }

    /**
     * Apply the version gate and (if needed) the migration chain to a manifest
     * tree, returning a current-shape tree. Public so a caller already holding a
     * parsed tree can bind it directly.
     */
    fun migrateManifestTree(manifest: ObjectNode): ObjectNode = migrateManifestTree(manifest, byFrom, baseline, current)

    /** The content/wire schema version this instance produces. */
    val currentVersion: Int get() = current

    /**
     * True when at least one migration step is registered. False during the
     * pre-release transitional window (`baseline == current`, empty chain),
     * where the at-rest migrator can skip scanning entirely.
     */
    val hasMigrations: Boolean get() = byFrom.isNotEmpty()

    /**
     * Upgrade a single stored content blob from [sourceVersion] (the row's
     * `schema_version`) to [currentVersion] by running the same registered chain
     * via [CatalogSchemaMigration.migrateContentBlob]. Drives the at-rest path
     * (see [AtRestContentMigrator]); the version lives in the carrier's
     * `schema_version` column, so the returned blob is **not** version-stamped.
     */
    fun migrateContentBlob(blobType: String, blob: JsonNode, sourceVersion: Int): JsonNode = migrateContentBlobTree(blobType, blob, sourceVersion, byFrom, baseline, current)

    companion object {
        private val logger = LoggerFactory.getLogger(CatalogSchemaMigrator::class.java)

        /**
         * Read `schemaVersion` from a catalog manifest tree as an integer.
         * Throws [CatalogSchemaUnknownException] if absent or non-integral.
         */
        fun readSchemaVersion(manifest: ObjectNode): Int {
            val node = manifest.get("schemaVersion")
                ?: throw CatalogSchemaUnknownException("missing 'schemaVersion'")
            if (!node.isIntegralNumber) {
                throw CatalogSchemaUnknownException("'schemaVersion' is not an integer (was: $node)")
            }
            return node.asInt()
        }

        /**
         * Pure form of the version gate + chain run, parameterised on the
         * version window so it is testable without Spring. See the class KDoc
         * for the decision table.
         */
        fun migrateManifestTree(
            manifest: ObjectNode,
            byFrom: Map<Int, CatalogSchemaMigration>,
            baseline: Int,
            current: Int,
        ): ObjectNode {
            val source = readSchemaVersion(manifest)
            return when {
                source > current -> throw CatalogSchemaTooNewException(source, current)
                source == current -> manifest
                // source < current
                byFrom.isEmpty() -> manifest // transitional: no chain yet — see class KDoc
                source < baseline -> throw CatalogSchemaTooOldException(source, baseline)
                else -> runManifestChain(manifest, source, byFrom, current)
            }
        }

        private fun runManifestChain(
            manifest: ObjectNode,
            source: Int,
            byFrom: Map<Int, CatalogSchemaMigration>,
            current: Int,
        ): ObjectNode {
            val ctx = MigrationContext(sourceVersion = source, targetVersion = current, manifest = null)
            var node = manifest
            var version = source
            while (version < current) {
                val step = byFrom[version]
                    ?: error("No migration from v$version (chain validated at startup — should be unreachable)")
                node = step.migrateManifest(node, ctx)
                version = step.to
            }
            node.put("schemaVersion", current)
            logger.debug("Migrated catalog manifest from wire schema v{} to v{}.", source, current)
            return node
        }

        /**
         * Pure form of the at-rest content-blob gate + chain run, parameterised
         * on the version window so it is testable without Spring. Mirrors
         * [migrateManifestTree]'s decision table, but the version is supplied by
         * the carrier's `schema_version` column (not read from the blob) and the
         * result is **not** version-stamped.
         */
        fun migrateContentBlobTree(
            blobType: String,
            blob: JsonNode,
            sourceVersion: Int,
            byFrom: Map<Int, CatalogSchemaMigration>,
            baseline: Int,
            current: Int,
        ): JsonNode = when {
            sourceVersion > current -> throw CatalogSchemaTooNewException(sourceVersion, current)
            sourceVersion == current -> blob
            // sourceVersion < current
            byFrom.isEmpty() -> blob // transitional: no chain yet — see migrateManifestTree
            sourceVersion < baseline -> throw CatalogSchemaTooOldException(sourceVersion, baseline)
            else -> runContentBlobChain(blobType, blob, sourceVersion, byFrom, current)
        }

        private fun runContentBlobChain(
            blobType: String,
            blob: JsonNode,
            source: Int,
            byFrom: Map<Int, CatalogSchemaMigration>,
            current: Int,
        ): JsonNode {
            val ctx = MigrationContext(sourceVersion = source, targetVersion = current, manifest = null)
            var node = blob
            var version = source
            while (version < current) {
                val step = byFrom[version]
                    ?: error("No migration from v$version (chain validated at startup — should be unreachable)")
                node = step.migrateContentBlob(blobType, node, ctx)
                version = step.to
            }
            return node
        }

        /**
         * Asserts the migration chain is contiguous and total: each step is
         * exactly one version (`to == from + 1`), no two steps share a `from`,
         * and the steps span `[baseline, current]` with no gaps. An empty chain
         * is valid iff `baseline == current` (nothing to migrate). Throws
         * [IllegalStateException] otherwise — called at construction so a
         * malformed chain fails application start.
         */
        fun validateMigrationChain(migrations: List<CatalogSchemaMigration>, baseline: Int, current: Int) {
            require(baseline <= current) { "baseline ($baseline) must be <= current ($current)" }

            val sorted = migrations.sortedBy { it.from }

            if (sorted.isEmpty()) {
                check(baseline == current) {
                    "Catalog schema migration chain is empty but baseline ($baseline) != current ($current): " +
                        "a migration from $baseline to $current is missing."
                }
                return
            }

            sorted.forEach { step ->
                check(step.to == step.from + 1) {
                    "Catalog schema migration ${step::class.simpleName} must advance exactly one version " +
                        "(from=${step.from}, to=${step.to})."
                }
            }

            val froms = sorted.map { it.from }
            check(froms.toSet().size == froms.size) {
                "Duplicate catalog schema migrations for version(s): " +
                    froms.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            }

            check(sorted.first().from == baseline) {
                "Catalog schema migration chain must start at baseline $baseline, " +
                    "but the first step is from ${sorted.first().from}."
            }
            check(sorted.last().to == current) {
                "Catalog schema migration chain must reach current $current, " +
                    "but the last step ends at ${sorted.last().to}."
            }
            for (i in 1 until sorted.size) {
                check(sorted[i].from == sorted[i - 1].to) {
                    "Gap in catalog schema migration chain between v${sorted[i - 1].to} and v${sorted[i].from}."
                }
            }
        }
    }
}
