package app.epistola.suite.catalog.migrations

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.CATALOG_BASELINE_SCHEMA_VERSION
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Upgrades an imported catalog payload to the current wire shape before binding,
 * by running an ordered chain of [CatalogSchemaMigration] steps over the **JSON
 * tree before binding**, then deserializing the current-shape tree into the
 * typed protocol model.
 *
 * **Catalog-wide versioning** (`docs/adr/0007-catalog-wire-format-migrations.md`):
 * the whole catalog has a single `schemaVersion`. The manifest is authoritative
 * for it, and every resource detail carries the same version. One contiguous
 * chain upgrades the manifest and every detail together; it is validated
 * contiguous and total at startup (see [validateMigrationChain]), so a malformed
 * chain fails application start (Flyway-like).
 *
 * This is the single migration chokepoint for both transports: the ZIP path
 * (`ImportCatalogZipHandler`) and the remote/classpath path
 * (`CatalogClient.fetchManifest`, shared by install / browse / upgrade-check /
 * fingerprint). Putting the gate in `CatalogClient` means read-only query paths
 * see migrated content too.
 *
 * **Version gate** (read from the payload's `schemaVersion`):
 *
 * - `== current` — bind directly (fast path; today's behaviour).
 * - `> current` — [CatalogSchemaTooNewException].
 * - `< current` with the chain **empty** — bind as-is. **Transitional:** no chain
 *   exists yet, so a sub-current payload is assumed already current-shape (how
 *   every such payload imports today). The first real migration replaces this
 *   with strict baseline enforcement.
 * - `< baseline` (only reachable once a chain exists) — [CatalogSchemaTooOldException].
 * - missing / non-integer `schemaVersion`, or unparseable JSON —
 *   [CatalogSchemaUnknownException].
 * - valid `schemaVersion` but a structure that fails to bind to the typed model
 *   (missing required fields, wrong node types) — also [CatalogSchemaUnknownException],
 *   so a broken-shape payload is a 400, not an unmapped 500.
 */
@Component
class CatalogSchemaMigrator(
    private val objectMapper: ObjectMapper,
    migrations: List<CatalogSchemaMigration>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The catalog-wide chain, keyed `from -> step`. Empty when no migrations exist. */
    private val chain: Map<Int, CatalogSchemaMigration>

    init {
        val steps = migrations.sortedBy { it.from }
        validateMigrationChain(steps, CATALOG_BASELINE_SCHEMA_VERSION, CATALOG_SCHEMA_VERSION)
        chain = steps.associateBy { it.from }
        if (chain.isEmpty()) {
            logger.info("Catalog wire-format migration chain is empty.")
        } else {
            logger.info("Catalog wire-format migration chain loaded: {} step(s).", chain.size)
        }
    }

    /**
     * Parse [rawManifest], gate/upgrade it to the current catalog schema version,
     * bind it to [CatalogManifest], and return it together with the
     * [CatalogMigrationContext] (the catalog's source version + migrated manifest
     * tree) that must be threaded into [migrateAndBindResourceDetail] for every
     * detail of the same catalog. Throws a [CatalogSchemaException] if the version
     * is unreadable, too new, or too old.
     */
    fun migrateAndBindManifest(rawManifest: ByteArray): MigratedManifest {
        val tree = parse(rawManifest)
        val sourceVersion = readSchemaVersion(tree)
        val migrated = migrate(tree, manifest = null) { step, node, ctx -> step.migrateManifest(node, ctx) }
        return MigratedManifest(
            manifest = bind(migrated, CatalogManifest::class.java, "catalog manifest"),
            catalog = CatalogMigrationContext(sourceVersion = sourceVersion, migratedManifest = migrated),
        )
    }

    /**
     * Parse [rawDetail], gate/upgrade it to the current catalog schema version,
     * and bind it to [ResourceDetail]. Invoked at both import chokepoints with the
     * [catalog] context from [migrateAndBindManifest].
     *
     * [type] is the manifest-declared resource type; the detail's own
     * `resource.type` discriminator must agree with it. The detail's own
     * `schemaVersion` must equal the catalog (manifest) version — a catalog is a
     * single bundle at one wire version, so a drifted per-detail stamp is a
     * malformed payload, rejected before any migration runs. The migrated manifest
     * tree is exposed to cross-part steps via [MigrationContext.manifest].
     */
    fun migrateAndBindResourceDetail(type: String, rawDetail: ByteArray, catalog: CatalogMigrationContext): ResourceDetail {
        val tree = parse(rawDetail)
        val declared = tree.get("resource")?.get("type")?.takeIf { it.isString }?.asString()
        if (declared != null && declared != type) {
            throw CatalogSchemaUnknownException(
                "resource detail declares type '$declared' but the manifest entry is '$type'",
            )
        }
        val detailVersion = readSchemaVersion(tree)
        if (detailVersion != catalog.sourceVersion) {
            throw CatalogSchemaUnknownException(
                "resource detail '$type' is at schemaVersion $detailVersion but the catalog manifest is at " +
                    "${catalog.sourceVersion}; every part of a catalog must carry the same wire version",
            )
        }
        val migrated = migrate(tree, manifest = catalog.migratedManifest) { step, node, ctx ->
            step.migrateResourceDetail(type, node, ctx)
        }
        return bind(migrated, ResourceDetail::class.java, "resource detail '$type'")
    }

    /** Apply the catalog version gate + chain to an already-parsed tree. */
    private fun migrate(
        tree: ObjectNode,
        manifest: ObjectNode?,
        apply: (CatalogSchemaMigration, ObjectNode, MigrationContext) -> ObjectNode,
    ): ObjectNode = migrate(tree, chain, CATALOG_BASELINE_SCHEMA_VERSION, CATALOG_SCHEMA_VERSION, manifest, apply)

    private fun parse(raw: ByteArray): ObjectNode {
        // Invalid JSON must surface as a schema-unknown error too (→ HTTP 400 /
        // inline UI fragment), not as a raw Jackson exception that escapes the
        // gate. This is the single parse point for both import chokepoints.
        val tree = try {
            objectMapper.readTree(raw)
        } catch (e: JacksonException) {
            throw CatalogSchemaUnknownException("payload is not valid JSON: ${e.originalMessage}")
        }
        return tree as? ObjectNode
            ?: throw CatalogSchemaUnknownException("payload is not a JSON object")
    }

    /**
     * Bind an already-gated, current-shape [tree] to the typed protocol model.
     * A payload can carry a valid `schemaVersion` yet still be structurally
     * malformed (missing required fields, wrong node types) — Jackson would throw
     * a raw bind exception that escapes the gate as an unmapped 500. Mirror
     * [parse]: surface it as a [CatalogSchemaUnknownException] (→ HTTP 400 / inline
     * UI fragment) so a broken-shape payload is reported as a bad request, not a
     * server error. [describe] names the part for the operator-facing message.
     */
    private fun <T : Any> bind(tree: ObjectNode, type: Class<T>, describe: String): T = try {
        objectMapper.treeToValue(tree, type)
    } catch (e: JacksonException) {
        throw CatalogSchemaUnknownException(
            "$describe has a valid schemaVersion but its structure does not bind: ${e.originalMessage}",
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CatalogSchemaMigrator::class.java)

        /**
         * Read `schemaVersion` from a catalog wire tree (manifest or resource
         * detail) as an integer. Throws [CatalogSchemaUnknownException] if absent
         * or non-integral.
         */
        fun readSchemaVersion(tree: ObjectNode): Int {
            val node = tree.get("schemaVersion")
                ?: throw CatalogSchemaUnknownException("missing 'schemaVersion'")
            if (!node.isIntegralNumber) {
                throw CatalogSchemaUnknownException("'schemaVersion' is not an integer (was: $node)")
            }
            return node.asInt()
        }

        /**
         * Pure form of the version gate + chain run, parameterised on the version
         * window and the per-step transform ([CatalogSchemaMigration.migrateManifest]
         * or [CatalogSchemaMigration.migrateResourceDetail]) so it is testable
         * without Spring. See the class KDoc for the decision table. Re-stamps
         * `schemaVersion` to [current].
         */
        fun migrate(
            tree: ObjectNode,
            byFrom: Map<Int, CatalogSchemaMigration>,
            baseline: Int,
            current: Int,
            manifest: ObjectNode? = null,
            apply: (CatalogSchemaMigration, ObjectNode, MigrationContext) -> ObjectNode,
        ): ObjectNode {
            val source = readSchemaVersion(tree)
            return when {
                source > current -> throw CatalogSchemaTooNewException(source, current)
                source == current -> tree
                // source < current
                byFrom.isEmpty() -> tree // transitional: no chain yet — see class KDoc
                source < baseline -> throw CatalogSchemaTooOldException(source, baseline)
                else -> runChain(tree, source, byFrom, current, manifest, apply)
            }
        }

        private fun runChain(
            tree: ObjectNode,
            source: Int,
            byFrom: Map<Int, CatalogSchemaMigration>,
            current: Int,
            manifest: ObjectNode?,
            apply: (CatalogSchemaMigration, ObjectNode, MigrationContext) -> ObjectNode,
        ): ObjectNode {
            val ctx = MigrationContext(sourceVersion = source, targetVersion = current, manifest = manifest)
            var node = tree
            var version = source
            while (version < current) {
                val step = byFrom[version]
                    ?: error("No migration from v$version (chain validated at startup — should be unreachable)")
                node = apply(step, node, ctx)
                version = step.to
            }
            node.put("schemaVersion", current)
            logger.debug("Migrated catalog wire schema v{} to v{}.", source, current)
            return node
        }

        /**
         * Asserts the migration chain is contiguous and total: each step is
         * exactly one version (`to == from + 1`), no two steps share a `from`, and
         * the steps span `[baseline, current]` with no gaps. An empty chain is
         * valid iff `baseline == current` (nothing to migrate). Throws
         * [IllegalStateException] otherwise — called at construction so a malformed
         * chain fails application start.
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

/**
 * Catalog-level context captured when the manifest is migrated, needed to migrate
 * every resource detail of the same catalog consistently.
 */
data class CatalogMigrationContext(
    /** The catalog version the export was produced at — the manifest's pre-migration `schemaVersion`. */
    val sourceVersion: Int,
    /** The migrated (current-shape) manifest tree, available to cross-part detail migrations. */
    val migratedManifest: ObjectNode,
)

/**
 * Result of [CatalogSchemaMigrator.migrateAndBindManifest]: the bound manifest plus
 * the [CatalogMigrationContext] to thread into each
 * [CatalogSchemaMigrator.migrateAndBindResourceDetail] call for the same catalog.
 */
data class MigratedManifest(
    val manifest: CatalogManifest,
    val catalog: CatalogMigrationContext,
)
