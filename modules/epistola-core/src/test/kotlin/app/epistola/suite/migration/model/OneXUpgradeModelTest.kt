// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.model

import app.epistola.suite.config.JdbiConfig
import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.database.DatabasePressureMonitor
import app.epistola.suite.migration.LAST_1X
import app.epistola.suite.migration.preflight.UpgradePreflights
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestRuntimeLifecycle
import app.epistola.suite.testing.invariants.CatalogInvariants
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.ShrinkingMode
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Random 1.x installations, upgraded to 2.0. Each is seeded on the last 1.x schema ([LAST_1X]) in a
 * database of its own, then put through what an upgrade runs: the preflight, then the migrations.
 *
 * - The preflight refuses exactly the installations whose kept content (each variant's working copy
 *   and latest published version, a template's theme) uses a catalog the tenant lacks, or makes
 *   catalogs use each other: what [UpgradePreflights] promises.
 * - Any other upgrades, and leaves catalogs no invariant faults ([CatalogInvariants]).
 * - Nothing is lost: each catalog's next draft holds every theme, stencil, template and variant, each
 *   with its working copy (its draft, else latest published, else latest archived version), and what
 *   each last published is in the "Published in 1.x" draft, or in the next draft when that was dropped
 *   for holding the same.
 * - A stencil instance whose copy is its stencil's pinned version, or that kept none, becomes a
 *   reference to the stencil; any other copy becomes plain content in its place, its text kept.
 * - Every 1.x activation is in the history summary.
 *
 * Content carries markers (`T cat-a/tpl-0/default v2`), so a check reads which version a draft holds.
 *
 * Opt-in (`@Tag("model")`, `./gradlew :modules:epistola-core:modelTest`): `-PmodelRuns=N` sets the
 * number of installations (default [DEFAULT_RUNS]); `-PmodelSeed=S` replays one. Seeded with raw SQL:
 * no command of today writes the 1.x schema. The Spring context is for the app's Jdbi
 * ([JdbiConfig]), which the invariants need, built here on each installation's database.
 */
@Tag("model")
class OneXUpgradeModelTest : IntegrationTestBase() {
    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var cipher: CredentialCipher

    @Autowired private lateinit var pressure: DatabasePressureMonitor

    // Kotest marks a seed and bounded shrinking in PropTestConfig experimental.
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `every 1x installation the preflight accepts upgrades to consistent catalogs that keep its content`() {
        val runs = System.getProperty("epistola.test.modelRuns")?.toInt() ?: DEFAULT_RUNS
        val config = PropTestConfig(seed = System.getProperty("epistola.test.modelSeed")?.toLong(), shrinkingMode = ShrinkingMode.Bounded(SHRINKS))
        val template = oneXTemplate()
        val stats = sortedMapOf<String, Int>()
        try {
            runBlocking { checkAll(runs, config, installation) { upgrade(it, template, stats) } }
        } finally {
            println("1.x upgrade model: $stats")
        }
    }

    private fun upgrade(installation: Installation, template: String, stats: MutableMap<String, Int>) {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "onex_model_${counter.incrementAndGet()}"
        admin { it.execute("CREATE DATABASE \"$database\" TEMPLATE \"$template\"") }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        try {
            DriverManager.getConnection(url, postgres.username, postgres.password).use { it.createStatement().use { st -> st.execute(installation.seed()) } }
            val expected = installation.refusals()
            val report = DriverManager.getConnection(url, postgres.username, postgres.password).use { UpgradePreflights.run(it) }
            val found = report.problems.map { (check, _) -> check.id }.toSortedSet()
            check(report.failed.isEmpty()) { "a preflight check failed to run: ${report.describe()}\n\n${installation.seed()}" }
            check(found == expected) { "the preflight found $found, expected $expected:\n${report.describe()}\n\n${installation.seed()}" }
            if (found.isNotEmpty()) {
                stats.merge("refused", 1, Int::plus)
                return
            }
            Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").load().migrate()
            val jdbi = JdbiConfig().jdbi(DriverManagerDataSource(url, postgres.username, postgres.password), mapper, cipher, pressure)
            val violations = CatalogInvariants.check(jdbi, TENANT)
            check(violations.isEmpty()) { "after the upgrade:\n${violations.joinToString("\n")}\n\n${installation.seed()}" }
            val lost = DriverManager.getConnection(url, postgres.username, postgres.password).use { Upgraded(it).lost(installation, stats) }
            check(lost.isEmpty()) { "after the upgrade:\n${lost.joinToString("\n")}\n\n${installation.seed()}" }
            stats.merge("upgraded", 1, Int::plus)
        } finally {
            admin { it.execute("DROP DATABASE IF EXISTS \"$database\" WITH (FORCE)") }
        }
    }

    /** The 1.x database every installation starts from, migrated to [LAST_1X] once per run. */
    private fun oneXTemplate(): String {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "onex_model_template"
        admin {
            it.execute("DROP DATABASE IF EXISTS \"$database\" WITH (FORCE)")
            it.execute("CREATE DATABASE \"$database\"")
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").target(LAST_1X).load().migrate()
        return database
    }

    private fun admin(block: (java.sql.Statement) -> Unit) {
        val postgres = TestRuntimeLifecycle.postgres()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection -> connection.createStatement().use(block) }
    }

    /** What a 1.x installation became: the drafts of each catalog, read for what they hold. */
    private class Upgraded(private val connection: Connection) {
        private fun rows(sql: String, vararg args: Any?): List<List<String?>> = connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
        }

        private fun draft(catalog: String, name: String?): String? = rows(
            """
            SELECT v.version_id::text FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
            WHERE v.tenant_key = ? AND c.catalog_key = ? AND v.state = 'draft' AND v.name IS NOT DISTINCT FROM ?
            """,
            TENANT,
            catalog,
            name,
        ).singleOrNull()?.single()

        /** The stored content of [type] [key] in [version], a variant under [template]; null when the version does not hold it. */
        private fun payload(version: String, type: String, key: String, template: String? = null): String? = rows(
            """
            SELECT c.payload::text FROM release_resources r
            JOIN resource_contents c ON c.tenant_key = r.tenant_key AND c.content_hash = r.content_hash
            WHERE r.tenant_key = ? AND r.version_id = ?::uuid AND r.resource_type = ? AND r.resource_key = ?
              AND (CAST(? AS text) IS NULL OR r.parent_id = (SELECT t.resource_id FROM release_resources t
                   WHERE t.tenant_key = r.tenant_key AND t.version_id = r.version_id AND t.resource_type = 'template' AND t.resource_key = ?))
            """,
            TENANT,
            version,
            type,
            key,
            template,
            template,
        ).singleOrNull()?.single()

        fun lost(installation: Installation, stats: MutableMap<String, Int>): List<String> = buildList {
            for (catalog in installation.catalogs) {
                val next = draft(catalog.key, null)
                if (next == null) {
                    add("${catalog.key} has no next draft")
                    continue
                }
                val named = draft(catalog.key, PUBLISHED_IN_1X)
                if (named != null) stats.merge("published-in-1x drafts", 1, Int::plus)
                // What was last published is in the named draft; without one, the next draft holds the same.
                val published = named ?: next

                fun expect(version: String, what: String, type: String, key: String, template: String?, markers: List<String>) {
                    val held = payload(version, type, key, template)
                    if (held == null) {
                        add("${catalog.key} ${if (version == next) "next draft" else PUBLISHED_IN_1X} lacks $what")
                        return
                    }
                    markers.filterNot { it in held }.forEach { add("${catalog.key} ${if (version == next) "next draft" else PUBLISHED_IN_1X}: $what lacks '$it', holds $held") }
                }

                fun absent(version: String, what: String, type: String, key: String, template: String?) {
                    if (payload(version, type, key, template) != null) add("${catalog.key} $PUBLISHED_IN_1X holds $what, never published")
                }

                catalog.themes().forEach { expect(next, "theme $it", "theme", it, null, emptyList()) }
                // A stencil never published is in the named draft only when published content there uses it.
                val usedByPublished = catalog.templates.filter { it.variants.first().versions.lastPublished() != null }.flatMap { template ->
                    template.variants.flatMap { variant -> variant.versions.lastPublished()?.let { variant.versionAt(it).uses }.orEmpty() }
                }.filter { use -> use.catalogIn(catalog) == catalog.key && becomesReference(use, catalog) }.map { it.stencil }.toSet()
                catalog.stencils.forEachIndexed { i, stencil ->
                    val key = stencilKey(i)
                    expect(next, "stencil $key", "stencil", key, null, listOfNotNull(stencil.versions.working()?.let { stencilMarker(catalog.key, key, it) }))
                    val last = stencil.versions.lastPublished()
                    if (last != null) {
                        expect(published, "stencil $key", "stencil", key, null, listOf(stencilMarker(catalog.key, key, last)))
                    } else if (i in usedByPublished) {
                        expect(published, "stencil $key", "stencil", key, null, listOfNotNull(stencil.versions.working()?.let { stencilMarker(catalog.key, key, it) }))
                    } else if (named != null) {
                        absent(named, "stencil $key", "stencil", key, null)
                    }
                }
                catalog.templates.forEachIndexed { t, template ->
                    val key = templateKey(t)
                    val contract = template.contracts.withIndex().lastOrNull { it.value == DRAFT } ?: template.contracts.withIndex().lastOrNull()
                    expect(next, "template $key", "template", key, null, listOfNotNull(contract?.let { contractMarker(catalog.key, key, it.index + 1) }))
                    val defaultPublished = template.variants.first().versions.lastPublished() != null
                    if (defaultPublished) {
                        val lastContract = template.contracts.withIndex().lastOrNull { it.value == PUBLISHED }
                        expect(published, "template $key", "template", key, null, listOfNotNull(lastContract?.let { contractMarker(catalog.key, key, it.index + 1) }))
                    } else if (named != null) {
                        absent(named, "template $key", "template", key, null)
                    }
                    template.variants.forEachIndexed { v, variant ->
                        val variantKey = VARIANTS[v]
                        val what = "variant $key/$variantKey"
                        val working = variant.versions.working()
                        expect(next, what, "variant", variantKey, key, working?.let { variantMarkers(installation, catalog, key, variantKey, it, variant.versionAt(it)) }.orEmpty())
                        val last = variant.versions.lastPublished()
                        if (last != null && defaultPublished) {
                            expect(published, what, "variant", variantKey, key, variantMarkers(installation, catalog, key, variantKey, last, variant.versionAt(last)))
                        } else if (named != null && defaultPublished) {
                            absent(named, what, "variant", variantKey, key)
                        }
                    }
                }
            }
            val activations = rows("SELECT count(*) FROM app_metadata, jsonb_array_elements(value -> 'activations') WHERE key = 'upgrade-1x-history'")
                .singleOrNull()?.single()?.toInt() ?: 0
            if (activations != installation.activations().size) add("the history summary lists $activations activations, not ${installation.activations().size}")
        }

        /** What a variant's version [id] shows after the upgrade: its own text, and per stencil instance a reference or the copy it kept. */
        private fun variantMarkers(installation: Installation, catalog: Catalog, template: String, variant: String, id: Int, version: Version): List<String> = listOf(templateMarker(catalog.key, template, variant, id)) + version.uses.mapNotNull { use ->
            when {
                becomesReference(use, installation.catalog(use.catalogIn(catalog))) -> "\"stencilKey\": \"${stencilKey(use.stencil)}\""
                use.copy == Copy.BARE -> null
                else -> use.copyText(use.catalogIn(catalog))
            }
        }
    }

    /** A 1.x installation: one tenant, its catalogs, and whether an environment activated what each variant last published. */
    data class Installation(val catalogs: List<Catalog>, val environment: Boolean) {
        fun catalog(key: String): Catalog? = catalogs.firstOrNull { it.key == key }

        fun activations(): List<Triple<String, String, Pair<String, Int>>> = if (!environment) {
            emptyList()
        } else {
            catalogs.flatMap { catalog ->
                catalog.templates.flatMapIndexed { t, template ->
                    template.variants.mapIndexedNotNull { v, variant ->
                        variant.versions.lastPublished()?.let { Triple(catalog.key, templateKey(t), VARIANTS[v] to it) }
                    }
                }
            }
        }

        /**
         * The preflight checks that must refuse this installation: what the upgrade keeps (each
         * variant's working copy and latest published version, a template's theme) using a
         * catalog the tenant lacks, or catalogs using each other.
         */
        fun refusals(): Set<String> {
            val uses = catalogs.flatMap { catalog ->
                val content = catalog.templates.flatMap { template ->
                    template.variants.flatMap { variant -> variant.versions.kept().flatMap { variant.versionAt(it).uses } }
                }.map { it.catalogIn(catalog) }
                val themes = catalog.templates.mapNotNull { template -> template.theme?.let { theme(it)?.first } }
                (content + themes).filter { it != catalog.key }.map { catalog.key to it }
            }.toSet()
            return buildSet {
                if (uses.any { (_, used) -> catalog(used) == null }) add("1x-missing-catalogs")
                if (cyclic(uses.filter { (_, used) -> catalog(used) != null }.toSet())) add("1x-catalog-dependency-cycles")
            }
        }

        /** The theme a template's [pick] names: a catalog and theme the installation has, counting round; null when it has none. */
        fun theme(pick: Pair<Int, Int>): Pair<String, String>? {
            val withThemes = catalogs.filter { it.themes > 0 }
            if (withThemes.isEmpty()) return null
            val catalog = withThemes[pick.first % withThemes.size]
            return catalog.key to themeKey(pick.second % catalog.themes)
        }

        fun seed(): String = buildString {
            appendLine("INSERT INTO tenants (id, name) VALUES ('$TENANT', 'Model');")
            for (catalog in catalogs) {
                appendLine("INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('${catalog.key}', '$TENANT', '${catalog.key}', 'AUTHORED');")
                RELEASES.take(catalog.releases).forEach {
                    appendLine("INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, manifest_snapshot) VALUES ('$TENANT', '${catalog.key}', '$it', '${"9".repeat(64)}', '{}');")
                }
                catalog.themes().forEach {
                    appendLine("INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles) VALUES ('$it', '$TENANT', '${catalog.key}', '$it', '{}');")
                }
                catalog.stencils.forEachIndexed { i, stencil ->
                    val key = stencilKey(i)
                    appendLine("INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('$key', '$TENANT', '${catalog.key}', '$key');")
                    stencil.versions.forEachIndexed { v, status ->
                        appendLine(
                            "INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at, archived_at) " +
                                "SELECT ${v + 1}, '$TENANT', resource_id, '${document(listOf(text("s", stencilMarker(catalog.key, key, v + 1))))}', '$status', ${published(status)}, ${archived(status)} " +
                                "FROM stencils WHERE tenant_key = '$TENANT' AND catalog_key = '${catalog.key}' AND id = '$key';",
                        )
                    }
                }
            }
            // Templates after every catalog's themes: a template's theme may be another catalog's.
            for (catalog in catalogs) {
                catalog.templates.forEachIndexed { t, template ->
                    val key = templateKey(t)
                    val where = "FROM document_templates WHERE tenant_key = '$TENANT' AND catalog_key = '${catalog.key}' AND id = '$key'"
                    val theme = template.theme?.let { theme(it) }
                        ?.let { (themeCatalog, themeKey) -> "(SELECT resource_id FROM themes WHERE tenant_key = '$TENANT' AND catalog_key = '$themeCatalog' AND id = '$themeKey')" }
                    appendLine("INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_resource_id) VALUES ('$key', '$TENANT', '${catalog.key}', '$key', ${theme ?: "NULL"});")
                    template.contracts.forEachIndexed { c, status ->
                        appendLine(
                            "INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, status, published_at) " +
                                "SELECT ${c + 1}, '$TENANT', resource_id, '{\"type\": \"object\", \"properties\": {}, \"description\": \"${contractMarker(catalog.key, key, c + 1)}\"}', '$status', ${published(status)} $where;",
                        )
                    }
                    template.variants.forEachIndexed { v, variant ->
                        val variantKey = VARIANTS[v]
                        appendLine("INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default) SELECT '$variantKey', '$TENANT', resource_id, '$variantKey', ${v == 0} $where;")
                        variant.versions.forEachIndexed { n, status ->
                            val version = n + 1
                            val nodes = listOf(text("t", templateMarker(catalog.key, key, variantKey, version))) +
                                variant.versionAt(version).uses.mapIndexed { u, use -> use.instance("u$u", catalog, this@Installation) }
                            appendLine(
                                "INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at, archived_at) " +
                                    "SELECT $version, '$TENANT', resource_id, '$variantKey', '${document(nodes)}', '$status', ${published(status)}, ${archived(status)} $where;",
                            )
                        }
                    }
                }
            }
            if (environment) {
                appendLine("INSERT INTO environments (id, tenant_key, name) VALUES ('production', '$TENANT', 'Production');")
                activations().forEach { (catalog, template, at) ->
                    appendLine(
                        "INSERT INTO environment_activations (tenant_key, environment_key, variant_key, version_key, template_resource_id) " +
                            "SELECT '$TENANT', 'production', '${at.first}', ${at.second}, resource_id FROM document_templates WHERE tenant_key = '$TENANT' AND catalog_key = '$catalog' AND id = '$template';",
                    )
                }
            }
        }
    }

    data class Catalog(val key: String, val releases: Int, val themes: Int, val stencils: List<Stencil>, val templates: List<Template>) {
        fun themes(): List<String> = (0 until themes).map(::themeKey)
    }

    /** A stencil and the status of each of its versions, numbered from 1. */
    data class Stencil(val versions: List<String>)

    /** A template: its theme as a pick ([Installation.theme]), its variants (the first the default), its data contract versions. */
    data class Template(val theme: Pair<Int, Int>?, val variants: List<Variant>, val contracts: List<String>)

    data class Variant(val versions: List<String>, val content: List<Version>) {
        /** Version [id]'s content; versions beyond the generated content use none. */
        fun versionAt(id: Int): Version = content.getOrElse(id - 1) { Version(emptyList()) }
    }

    /** What a template version holds beside its own text: a stencil instance per use. */
    data class Version(val uses: List<Use>)

    /**
     * A stencil instance: of stencil [stencil] of catalog [catalog] (an index into [POOL], the last
     * one never created; null for the template's own), pinned to version [pinned], keeping [copy].
     */
    data class Use(val catalog: Int?, val stencil: Int, val pinned: Int?, val copy: Copy) {
        fun catalogIn(owner: Catalog): String = catalog?.let { POOL[it] } ?: owner.key

        fun copyText(target: String): String = when (copy) {
            Copy.EDITED -> "E $target/${stencilKey(stencil)} edited"
            else -> stencilMarker(target, stencilKey(stencil), pinned ?: 0)
        }

        fun instance(id: String, owner: Catalog, installation: Installation): Node {
            val props = buildList {
                add("\"stencilId\": \"${stencilKey(stencil)}\"")
                catalog?.let { add("\"catalogKey\": \"${POOL[it]}\"") }
                pinned?.let { add("\"version\": $it") }
            }.joinToString(", ", "{", "}")
            if (copy == Copy.BARE) return Node(id, """{"id": "$id", "type": "stencil", "slots": [], "props": $props}""", emptyMap())
            val copyNode = text("$id-copy", copyText(catalogIn(owner)))
            return Node(
                id,
                """{"id": "$id", "type": "stencil", "slots": ["$id:children"], "props": $props}""",
                mapOf("$id:children" to """{"id": "$id:children", "name": "children", "nodeId": "$id", "children": ["$id-copy"]}""") + copyNode.slots,
                listOf(copyNode),
            )
        }
    }

    /** What a stencil instance kept of its stencil: nothing, its pinned version's content, or that content edited. */
    enum class Copy { BARE, EXACT, EDITED }

    /** A node of a 1.x document as JSON, with the slots it owns and the nodes in them. */
    class Node(val id: String, val json: String, val slots: Map<String, String>, val children: List<Node> = emptyList())

    private companion object {
        const val DEFAULT_RUNS = 100
        const val SHRINKS = 100
        const val TENANT = "onex-model"
        const val PUBLISHED_IN_1X = "Published in 1.x"
        const val DRAFT = "draft"
        const val PUBLISHED = "published"
        const val ARCHIVED = "archived"

        /** Catalog keys: an installation has some of the first three; the last is never created. */
        val POOL = listOf("cat-a", "cat-b", "cat-c", "cat-x")
        val RELEASES = listOf("1.0.0", "1.1.0")
        val VARIANTS = listOf("default", "alt")

        val counter = AtomicInteger()

        fun themeKey(i: Int) = "theme-$i"

        fun stencilKey(i: Int) = "stencil-$i"

        fun templateKey(i: Int) = "tpl-$i"

        fun stencilMarker(catalog: String, stencil: String, version: Int) = "S $catalog/$stencil v$version"

        fun templateMarker(catalog: String, template: String, variant: String, version: Int) = "T $catalog/$template/$variant v$version"

        fun contractMarker(catalog: String, template: String, version: Int) = "C $catalog/$template v$version"

        fun published(status: String) = if (status == DRAFT) "NULL" else "NOW()"

        fun archived(status: String) = if (status == ARCHIVED) "NOW()" else "NULL"

        /** The working copy's version number: the draft, else the latest published, else the latest archived; null without versions. */
        fun List<String>.working(): Int? = (indexOfLast { it == DRAFT }.takeIf { it >= 0 } ?: indexOfLast { it == PUBLISHED }.takeIf { it >= 0 } ?: lastIndex.takeIf { it >= 0 })?.plus(1)

        /**
         * Whether [use] becomes a reference to its stencil in [target]: the stencil exists, and the
         * instance kept no copy or one of a version it has. Any other copy becomes plain content.
         */
        fun becomesReference(use: Use, target: Catalog?): Boolean {
            val stencil = target?.stencils?.getOrNull(use.stencil) ?: return false
            return use.copy == Copy.BARE || (use.copy == Copy.EXACT && use.pinned?.let { stencil.versions.getOrNull(it - 1) } != null)
        }

        fun List<String>.lastPublished(): Int? = indexOfLast { it == PUBLISHED }.takeIf { it >= 0 }?.plus(1)

        /** The versions the upgrade keeps and the preflight reads: the working copy and the latest published. */
        fun List<String>.kept(): List<Int> = listOfNotNull(working(), lastPublished()).distinct()

        fun text(id: String, text: String) = Node(
            id,
            """{"id": "$id", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}]}}}""",
            emptyMap(),
        )

        /** A 1.x document: a root holding [roots] in its children slot. */
        fun document(roots: List<Node>): String {
            val all = generateSequence(roots) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().toList()
            val nodes = (listOf("\"root\": {\"id\": \"root\", \"type\": \"root\", \"slots\": [\"root:children\"]}") + all.map { "\"${it.id}\": ${it.json}" }).joinToString(", ")
            val slots = (
                listOf("\"root:children\": {\"id\": \"root:children\", \"name\": \"children\", \"nodeId\": \"root\", \"children\": [${roots.joinToString(", ") { "\"${it.id}\"" }}]}") +
                    all.flatMap { node -> node.slots.map { (id, json) -> "\"$id\": $json" } }
                ).joinToString(", ")
            return """{"root": "root", "nodes": {$nodes}, "slots": {$slots}}"""
        }

        /** Whether [edges] (catalog to catalog it uses) hold a cycle. */
        fun cyclic(edges: Set<Pair<String, String>>): Boolean {
            val next = edges.groupBy({ it.first }, { it.second })
            val done = mutableSetOf<String>()
            val onPath = mutableSetOf<String>()
            fun visit(node: String): Boolean {
                if (node in onPath) return true
                if (!done.add(node)) return false
                onPath += node
                val found = next[node].orEmpty().any(::visit)
                onPath -= node
                return found
            }
            return next.keys.any { visit(it) }
        }

        /** A version history: some archived or published versions, then perhaps a draft. */
        val versions: Arb<List<String>> = Arb.bind(Arb.list(Arb.element(ARCHIVED, PUBLISHED), 0..3), Arb.boolean()) { past, draft -> if (draft) past + DRAFT else past }

        val use: Arb<Use> = Arb.bind(
            Arb.choose(6 to Arb.constant<Int?>(null), 3 to Arb.int(0..2), 1 to Arb.constant<Int?>(3)),
            Arb.int(0..2),
            Arb.int(1..4).orNull(0.2),
            Arb.enum<Copy>(),
            ::Use,
        )

        val variant: Arb<Variant> = Arb.bind(versions, Arb.list(Arb.list(use, 0..2).map(::Version), 0..4), ::Variant)

        val template: Arb<Template> = Arb.bind(
            Arb.bind(Arb.int(0..2), Arb.int(0..1)) { c, t -> c to t }.orNull(0.4),
            Arb.list(variant, 1..2),
            Arb.bind(Arb.int(0..2), Arb.boolean()) { published, draft -> List(published) { PUBLISHED } + listOfNotNull(DRAFT.takeIf { draft }) },
            ::Template,
        )

        /** A catalog, keyed when the installation places it. */
        val catalog: Arb<Catalog> = Arb.bind(Arb.int(0..2), Arb.int(0..2), Arb.list(versions.map(::Stencil), 0..3), Arb.list(template, 0..2)) { releases, themes, stencils, templates ->
            Catalog("", releases, themes, stencils, templates)
        }

        /** Some of the first three catalogs of [POOL] (a bit each, at least one), each generated. */
        val installation: Arb<Installation> = Arb.bind(Arb.int(1..7), Arb.list(catalog, 3..3), Arb.boolean()) { present, catalogs, environment ->
            Installation((0..2).filter { present and (1 shl it) != 0 }.map { catalogs[it].copy(key = POOL[it]) }, environment)
        }
    }
}
