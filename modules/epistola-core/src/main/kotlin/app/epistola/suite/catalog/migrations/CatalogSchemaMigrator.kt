package app.epistola.suite.catalog.migrations

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.suite.catalog.CATALOG_PART_SCHEMAS
import app.epistola.suite.catalog.CatalogPart
import app.epistola.suite.catalog.PartSchemaWindow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Upgrades an imported catalog payload to the current wire shape before binding,
 * by running an ordered chain of [CatalogSchemaMigration] steps over the **JSON
 * tree before binding**, then deserializing the current-shape tree into the
 * typed protocol model.
 *
 * **Per-part versioning** (`docs/adr/0006-catalog-wire-format-migrations.md`):
 * each [CatalogPart] — the manifest and each resource type — is versioned
 * independently with its own chain and its own [PartSchemaWindow]. The manifest
 * is gated by its own `schemaVersion`; each resource detail by its own. Steps
 * are grouped by [CatalogSchemaMigration.part]; each part's chain is validated
 * **contiguous and total** at startup (see [validateMigrationChain]), so a
 * malformed chain fails application start (Flyway-like).
 *
 * This is the single migration chokepoint for both transports: the ZIP path
 * (`ImportCatalogZipHandler`) and the remote/classpath path
 * (`CatalogClient.fetchManifest`, shared by install / browse / upgrade-check /
 * fingerprint). Putting the gate in `CatalogClient` means read-only query paths
 * see migrated content too.
 *
 * **Version gate** (per part, read from that part's tree `schemaVersion`):
 *
 * - `== current` — bind directly (fast path; today's behaviour).
 * - `> current` — [CatalogSchemaTooNewException].
 * - `< current` with the part's chain **empty** — bind as-is. **Transitional:**
 *   no chain exists for the part yet and pre-versioning stamps are unreliable,
 *   so a sub-current payload is assumed already current-shape (how every such
 *   payload imports today). The first real migration for the part replaces this
 *   with strict baseline enforcement.
 * - `< baseline` (only reachable once the part has a chain) —
 *   [CatalogSchemaTooOldException].
 * - missing / non-integer `schemaVersion` — [CatalogSchemaUnknownException].
 *
 * The resource-detail path ([migrateAndBindResourceDetail]) is wired at both
 * chokepoints — `ImportCatalogZip` (stencil pre-scan + per-resource reads) and
 * [app.epistola.suite.catalog.CatalogClient.fetchResourceDetail] — gating each
 * detail by **its own** `schemaVersion` against that resource type's window.
 */
@Component
class CatalogSchemaMigrator(
    private val objectMapper: ObjectMapper,
    migrations: List<CatalogSchemaMigration>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Per-part chains, keyed `part -> (from -> step)`. Empty for parts with no migrations. */
    private val chainsByPart: Map<CatalogPart, Map<Int, CatalogSchemaMigration>>

    init {
        val byPart = migrations.groupBy { it.part }
        // Validate every part's chain against that part's window — including parts
        // with no migrations (an empty chain is valid iff baseline == current).
        chainsByPart = CATALOG_PART_SCHEMAS.mapValues { (part, window) ->
            val steps = (byPart[part] ?: emptyList()).sortedBy { it.from }
            validateMigrationChain(steps, window.baseline, window.current)
            steps.associateBy { it.from }
        }
        val total = chainsByPart.values.sumOf { it.size }
        if (total == 0) {
            logger.info("Catalog wire-format migration chains are all empty.")
        } else {
            logger.info("Catalog wire-format migration chains loaded: {} step(s) across {} part(s).", total, chainsByPart.count { it.value.isNotEmpty() })
        }
    }

    /**
     * Parse [rawManifest], gate/upgrade the **manifest** part to its current
     * schema version, and bind it to [CatalogManifest]. Throws a
     * [CatalogSchemaException] if the version is unreadable, too new, or too old.
     */
    fun migrateAndBindManifest(rawManifest: ByteArray): CatalogManifest {
        val migrated = migratePartTree(CatalogPart.MANIFEST, parse(rawManifest))
        return objectMapper.treeToValue(migrated, CatalogManifest::class.java)
    }

    /**
     * Parse [rawDetail], gate/upgrade the resource [type]'s detail to its part's
     * current schema version, and bind it to [ResourceDetail]. Invoked at both
     * import chokepoints (see class KDoc).
     *
     * [type] is the manifest-declared resource type; the detail's own
     * `resource.type` discriminator must agree with it, otherwise the payload
     * would be gated/migrated against the wrong part's window. A mismatch is
     * rejected as a malformed payload before any migration runs.
     */
    fun migrateAndBindResourceDetail(type: String, rawDetail: ByteArray): ResourceDetail {
        val part = CatalogPart.ofResourceType(type)
            ?: throw CatalogSchemaUnknownException("unknown resource type '$type'")
        val tree = parse(rawDetail)
        val declared = tree.get("resource")?.get("type")?.takeIf { it.isString }?.asString()
        if (declared != null && declared != type) {
            throw CatalogSchemaUnknownException(
                "resource detail declares type '$declared' but the manifest entry is '$type'",
            )
        }
        val migrated = migratePartTree(part, tree)
        return objectMapper.treeToValue(migrated, ResourceDetail::class.java)
    }

    /** Apply [part]'s version gate + chain to an already-parsed tree, returning a current-shape tree. */
    fun migratePartTree(part: CatalogPart, tree: ObjectNode): ObjectNode {
        val window = CATALOG_PART_SCHEMAS.getValue(part)
        return migratePartTree(tree, chainsByPart.getValue(part), window.baseline, window.current)
    }

    private fun parse(raw: ByteArray): ObjectNode = objectMapper.readTree(raw) as? ObjectNode
        ?: throw CatalogSchemaUnknownException("payload is not a JSON object")

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
         * Pure form of the version gate + chain run for one part, parameterised on
         * the version window so it is testable without Spring. See the class KDoc
         * for the decision table. Runs [CatalogSchemaMigration.migrate] for each
         * step and re-stamps `schemaVersion` to [current].
         */
        fun migratePartTree(
            tree: ObjectNode,
            byFrom: Map<Int, CatalogSchemaMigration>,
            baseline: Int,
            current: Int,
        ): ObjectNode {
            val source = readSchemaVersion(tree)
            return when {
                source > current -> throw CatalogSchemaTooNewException(source, current)
                source == current -> tree
                // source < current
                byFrom.isEmpty() -> tree // transitional: no chain yet — see class KDoc
                source < baseline -> throw CatalogSchemaTooOldException(source, baseline)
                else -> runChain(tree, source, byFrom, current)
            }
        }

        private fun runChain(
            tree: ObjectNode,
            source: Int,
            byFrom: Map<Int, CatalogSchemaMigration>,
            current: Int,
        ): ObjectNode {
            val ctx = MigrationContext(sourceVersion = source, targetVersion = current)
            var node = tree
            var version = source
            while (version < current) {
                val step = byFrom[version]
                    ?: error("No migration from v$version (chain validated at startup — should be unreachable)")
                node = step.migrate(node, ctx)
                version = step.to
            }
            node.put("schemaVersion", current)
            logger.debug("Migrated catalog part from wire schema v{} to v{}.", source, current)
            return node
        }

        /**
         * Asserts one part's migration chain is contiguous and total: each step is
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
