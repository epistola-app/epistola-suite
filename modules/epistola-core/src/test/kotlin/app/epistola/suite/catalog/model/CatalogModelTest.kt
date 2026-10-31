// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.model

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.DeleteCatalog
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.invariants.CatalogInvariants
import app.epistola.suite.testing.testAddress
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.ShrinkingMode
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Random sequences of real catalog commands, with the catalog invariants ([CatalogInvariants])
 * checked after every step. A command may be refused; it may not crash, and nothing it is allowed
 * to do may leave the tenant's catalogs inconsistent. A failing sequence is shrunk to the shortest
 * one that still fails and reported with its seed.
 *
 * A step names what it acts on by a pick, resolved when it runs against what exists then (read from
 * the database), and now and then a name nothing has: so steps mostly meet each other's work — a
 * delete meets something in use, a release a dependency, a move a name taken — and refusals stay
 * exercised. Small pools of names (three catalogs, two of each resource) add collisions.
 *
 * Opt-in, out of `test` and `integrationTest`: `./gradlew :modules:epistola-core:modelTest`.
 * `-PmodelRuns=N` sets the number of sequences (default [DEFAULT_RUNS]); `-PmodelSeed=S` replays one.
 */
@Tag("model")
class CatalogModelTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    // Kotest marks a seed and bounded shrinking in PropTestConfig experimental.
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `no sequence of catalog commands leaves the catalogs inconsistent`() {
        val stats = Stats()
        val runs = System.getProperty("epistola.test.modelRuns")?.toInt() ?: DEFAULT_RUNS
        val config = PropTestConfig(seed = System.getProperty("epistola.test.modelSeed")?.toLong(), shrinkingMode = ShrinkingMode.Bounded(SHRINKS))
        try {
            runBlocking { checkAll(runs, config, Arb.list(step, 1..MAX_STEPS)) { steps -> run(steps, stats) } }
        } finally {
            println("Catalog model: ${stats.report()}")
        }
    }

    private fun run(steps: List<Step>, stats: Stats) {
        val world = World(createTenant("Model").id, jdbi)
        withMediator { STARTING.forEach { CreateCatalog(tenantKey = world.tenant, id = CatalogKey.of(it), name = it).execute() } }
        val done = mutableListOf<String>()
        steps.forEachIndexed { index, step ->
            var did = step.toString()
            val outcome = try {
                withMediator { step.apply(world) }?.let {
                    did = it
                    "done"
                } ?: "nothing to do"
            } catch (e: Exception) {
                if (crashed(e)) throw AssertionError("step ${index + 1}, $did, crashed after:${trace(done)}", e)
                stats.example(e)
                e::class.simpleName ?: "refused"
            }
            done += "$did: $outcome"
            stats.record(step, outcome)
            val violations = CatalogInvariants.check(jdbi, world.tenant.value)
            if (violations.isNotEmpty()) throw AssertionError("after step ${index + 1}:\n  ${violations.joinToString("\n  ")}\nsteps:${trace(done)}")
        }
    }

    private fun trace(done: List<String>) = done.joinToString("\n  ", "\n  ")

    /** An exception no refusal raises: the code did not expect the state it met. */
    private fun crashed(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
        it is NullPointerException || it is ClassCastException || it is IndexOutOfBoundsException ||
            it is NoSuchElementException || it is java.sql.SQLException || it is org.springframework.dao.DataAccessException ||
            it is org.jdbi.v3.core.JdbiException
    }

    // ── The world ──

    private class World(val tenant: TenantKey, private val jdbi: Jdbi) {
        /** Releases cut in this sequence, per catalog, oldest first. */
        val releases = mutableMapOf<String, MutableList<String>>()

        fun catalog(key: String): CatalogId = testCatalog(tenant, CatalogKey.of(key))

        /** The pool's catalogs the tenant has. */
        fun catalogs(): List<String> = jdbi.withHandle<List<String>, Exception> {
            it.createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<pool>) ORDER BY catalog_key")
                .bind("t", tenant).bindList("pool", CATALOGS).mapTo(String::class.java).list()
        }

        /** The [type] resources in the pool catalogs' drafts, as catalog and key. */
        fun existing(type: String): List<Pair<String, String>> = jdbi.withHandle<List<Pair<String, String>>, Exception> {
            it.createQuery(
                """
                SELECT c.catalog_key, r.resource_key FROM release_resources r
                JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.next_version_id = r.version_id
                WHERE r.tenant_key = :t AND r.resource_type = :type AND r.parent_id IS NULL AND c.catalog_key IN (<pool>)
                ORDER BY 1, 2
                """,
            ).bind("t", tenant).bind("type", type).bindList("pool", CATALOGS)
                .map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
        }

        /** The key of [catalog]'s next version, by which a declaration may name it instead of a release. */
        fun nextVersionKey(catalog: String): String = jdbi.withHandle<String, Exception> {
            it.createQuery(
                "SELECT v.version_key FROM catalogs c JOIN catalog_versions v ON v.tenant_key = c.tenant_key AND v.version_id = c.next_version_id WHERE c.tenant_key = :t AND c.catalog_key = :c",
            ).bind("t", tenant).bind("c", catalog).mapTo(String::class.java).one()
        }

        /** What [key] declares, as a declaration of it is written. */
        fun declarations(key: String): List<DeclaredCatalogDependency> = ListCatalogDependencies(catalog(key)).query()
            .map { DeclaredCatalogDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion, it.draftId) }
    }

    // ── The steps ──

    private sealed interface Step {
        /** Runs the step and says what it did, or null when there was nothing for it to act on. */
        fun apply(world: World): String?
    }

    private data class AddCatalog(val name: String) : Step {
        override fun apply(world: World): String {
            CreateCatalog(tenantKey = world.tenant, id = CatalogKey.of(name), name = name).execute()
            return "add catalog $name"
        }
    }

    private data class RemoveCatalog(val pick: Int) : Step {
        override fun apply(world: World): String {
            val catalog = world.catalogs().pick(pick) { CATALOGS[pick % CATALOGS.size] }
            DeleteCatalog(world.catalog(catalog)).execute()
            return "remove catalog $catalog"
        }
    }

    /** Adds a resource of [type] named [name] to a catalog picked by [pick]. */
    private data class Add(val type: String, val pick: Int, val name: String) : Step {
        override fun apply(world: World): String {
            val catalog = world.catalogs().pick(pick) { CATALOGS[pick % CATALOGS.size] }
            val at = world.catalog(catalog)
            when (type) {
                STENCIL -> CreateStencil(StencilId(StencilKey.of(name), at), name).execute()
                THEME -> CreateTheme(ThemeId(ThemeKey.of(name), at), name).execute()
                else -> CreateDocumentTemplate(TemplateId(TemplateKey.of(name), at), name).execute().withRequiredDataExample()
            }
            return "add $type $catalog/$name"
        }
    }

    private data class Remove(val type: String, val pick: Int) : Step {
        override fun apply(world: World): String {
            val (catalog, key) = world.existing(type).pick(pick) { CATALOGS[pick % CATALOGS.size] to "missing-$type" }
            val at = world.catalog(catalog)
            when (type) {
                STENCIL -> DeleteStencil(StencilId(StencilKey.of(key), at)).execute()
                else -> DeleteTheme(ThemeId(ThemeKey.of(key), at)).execute()
            }
            return "remove $type $catalog/$key"
        }
    }

    /** Saves a template's content as instances of stencils, each by a pick; one from another catalog names it. */
    private data class UseStencils(val pick: Int, val stencils: List<Int>) : Step {
        override fun apply(world: World): String? {
            val (catalog, template) = world.existing(TEMPLATE).nth(pick) ?: return null
            val available = world.existing(STENCIL)
            val uses = stencils.map { available.pick(it) { CATALOGS[it % CATALOGS.size] to "missing-stencil" } }
                .map { (from, stencil) -> stencil to from.takeIf { it != catalog } }
            val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of(template), world.catalog(catalog)))
            UpdateVariantContent(variant, model(uses)).execute()
            return "use in $catalog/$template: ${uses.joinToString { (stencil, from) -> "${from ?: catalog}/$stencil" }}"
        }
    }

    private data class UseTheme(val pick: Int, val theme: Int) : Step {
        override fun apply(world: World): String? {
            val (catalog, template) = world.existing(TEMPLATE).nth(pick) ?: return null
            val (themeCatalog, key) = world.existing(THEME).pick(theme) { CATALOGS[theme % CATALOGS.size] to "missing-theme" }
            UpdateDocumentTemplate(TemplateId(TemplateKey.of(template), world.catalog(catalog)), themeId = ThemeKey.of(key), themeCatalog = world.catalog(themeCatalog)).execute()
            return "theme of $catalog/$template: $themeCatalog/$key"
        }
    }

    private data class Release(val pick: Int, val withDependencies: Boolean) : Step {
        override fun apply(world: World): String? {
            val catalog = world.catalogs().nth(pick) ?: return null
            MarkCatalogReady(world.catalog(catalog)).execute()
            val released = ReleaseCatalogDraft(world.catalog(catalog), releaseDependencies = withDependencies).execute()
            world.releases.getOrPut(catalog) { mutableListOf() } += released.version
            return "release $catalog ${released.version}${if (withDependencies) " with its dependencies" else ""}"
        }
    }

    /** Deletes the [nth] release this sequence cut of a catalog, counting round. */
    private data class RemoveRelease(val pick: Int, val nth: Int) : Step {
        override fun apply(world: World): String? {
            val catalog = world.releases.filterValues { it.isNotEmpty() }.keys.sorted().nth(pick) ?: return null
            val releases = world.releases.getValue(catalog)
            val version = releases[nth % releases.size]
            DeleteCatalogRelease(world.catalog(catalog), version).execute()
            releases.remove(version)
            return "remove release $catalog $version"
        }
    }

    /**
     * Declares one catalog for another: pinned to the [nth] release this sequence cut of it, or, with
     * [nth] null or no release, naming its next version. The other declarations stay.
     */
    private data class Declare(val pick: Int, val dependency: Int, val nth: Int?) : Step {
        override fun apply(world: World): String? {
            val catalogs = world.catalogs().takeIf { it.size >= 2 } ?: return null
            val catalog = catalogs[pick % catalogs.size]
            val on = (catalogs - catalog).nth(dependency) ?: return null
            val target = GetCatalog(world.catalog(on)).query() ?: return null
            val releases = world.releases[on].orEmpty()
            val declared = if (nth != null && releases.isNotEmpty()) {
                DeclaredCatalogDependency(target.registry, target.namespace, on, releaseVersion = releases[nth % releases.size])
            } else {
                DeclaredCatalogDependency(target.registry, target.namespace, on, versionId = world.nextVersionKey(on))
            }
            UpdateCatalogDependencies(world.catalog(catalog), world.declarations(catalog).filter { it.catalogKey != on } + declared).execute()
            return "declare for $catalog: $on ${declared.releaseVersion ?: "next version"}"
        }
    }

    private data class Undeclare(val pick: Int, val dependency: Int) : Step {
        override fun apply(world: World): String? {
            val catalog = world.catalogs().nth(pick) ?: return null
            val declared = world.declarations(catalog)
            val dropped = declared.nth(dependency) ?: return null
            UpdateCatalogDependencies(world.catalog(catalog), declared - dropped).execute()
            return "undeclare for $catalog: ${dropped.catalogKey}"
        }
    }

    private data class Move(val type: String, val pick: Int, val to: Int) : Step {
        override fun apply(world: World): String? {
            val (from, key) = world.existing(type).nth(pick) ?: return null
            val into = world.catalogs().filter { it != from }.nth(to) ?: return null
            val resourceType = if (type == STENCIL) CatalogResourceType.STENCIL else CatalogResourceType.THEME
            val relocations = listOf(testAddress(world.tenant, resourceType, from, key).movedTo(CatalogKey.of(into)))
            val preview = PreviewCatalogResourceMove(world.tenant, relocations).query()
            MoveCatalogResources(world.tenant, relocations, preview.planFingerprint).execute()
            return "move $type $from/$key to $into"
        }
    }

    private class Stats {
        private val outcomes = sortedMapOf<String, MutableMap<String, Int>>()
        private val examples = sortedMapOf<String, String>()

        fun record(step: Step, outcome: String) {
            outcomes.getOrPut(step::class.simpleName!!) { sortedMapOf() }.merge(outcome, 1, Int::plus)
        }

        /** Keeps the first message of each kind of refusal, to read what refuses. */
        fun example(e: Exception) {
            examples.putIfAbsent(e::class.simpleName ?: "refused", e.message.orEmpty().take(200))
        }

        fun report(): String = outcomes.entries.joinToString("\n  ", "\n  ") { (step, counts) -> "$step: $counts" } +
            "\nRefusals, first of each:" + examples.entries.joinToString("\n  ", "\n  ") { (kind, message) -> "$kind: $message" }
    }

    private companion object {
        const val DEFAULT_RUNS = 100
        const val MAX_STEPS = 40
        const val SHRINKS = 200

        /** One pick in this many names nothing, to keep refusals exercised. */
        const val MISS = 8

        const val STENCIL = "stencil"
        const val THEME = "theme"
        const val TEMPLATE = "template"

        /** Catalogs each sequence starts with; [CATALOGS] adds one it may create. */
        val STARTING = listOf("cat-a", "cat-b")
        val CATALOGS = STARTING + "cat-c"
        val STENCILS = listOf("stencil-1", "stencil-2")
        val THEMES = listOf("theme-1", "theme-2")
        val TEMPLATES = listOf("template-1", "template-2")

        /** The [n]th of these, counting round; null when there are none. */
        fun <T> List<T>.nth(n: Int): T? = if (isEmpty()) null else this[n % size]

        /** The [n]th of these, counting round; or, every [MISS]th pick or when there are none, [otherwise]. */
        fun <T> List<T>.pick(n: Int, otherwise: () -> T): T = if (isEmpty() || n % MISS == 0) otherwise() else this[n % size]

        private val pick = Arb.int(0..999)

        val step: Arb<Step> = Arb.choose(
            1 to Arb.element(CATALOGS).map(::AddCatalog),
            1 to pick.map(::RemoveCatalog),
            3 to Arb.bind(pick, Arb.element(STENCILS)) { p, name -> Add(STENCIL, p, name) },
            2 to Arb.bind(pick, Arb.element(THEMES)) { p, name -> Add(THEME, p, name) },
            3 to Arb.bind(pick, Arb.element(TEMPLATES)) { p, name -> Add(TEMPLATE, p, name) },
            2 to pick.map { Remove(STENCIL, it) },
            1 to pick.map { Remove(THEME, it) },
            5 to Arb.bind(pick, Arb.list(pick, 0..3), ::UseStencils),
            2 to Arb.bind(pick, pick, ::UseTheme),
            3 to Arb.bind(pick, Arb.boolean(), ::Release),
            1 to Arb.bind(pick, Arb.int(0..2), ::RemoveRelease),
            2 to Arb.bind(pick, pick, Arb.int(0..2).orNull(0.5), ::Declare),
            2 to Arb.bind(pick, pick, ::Undeclare),
            2 to Arb.bind(Arb.element(STENCIL, THEME), pick, pick, ::Move),
        )

        /** Content made of one instance per use, each a stencil and the catalog it is from (null: the template's own). */
        fun model(uses: List<Pair<String, String?>>): TemplateDocument {
            val instances = uses.mapIndexed { i, (stencil, from) ->
                Node(id = "use-$i", type = "stencil", props = listOfNotNull("stencilKey" to stencil, from?.let { "catalogKey" to it }).toMap())
            }
            return TemplateDocument(
                modelVersion = 1,
                root = "root",
                nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))) + instances.associateBy { it.id },
                slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = instances.map { it.id })),
                themeRef = ThemeRef.Inherit,
            )
        }
    }
}
