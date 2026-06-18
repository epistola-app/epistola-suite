package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.validateMigrationChain
import app.epistola.suite.catalog.migrations.steps.CatalogV3ToV4ExampleMigration
import app.epistola.suite.catalog.migrations.steps.CatalogV4ToV5Migration
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * Startup chain-integrity checks for [CatalogSchemaMigrator] — the contiguous,
 * total, gap-free invariant that fails application start (Flyway-like) when the
 * catalog migration chain is malformed. Pure unit test; no Spring.
 */
class CatalogSchemaMigratorChainTest {

    /** A migration step that does nothing but declare its [from]/[to]. */
    private class NoopMigration(
        override val from: Int,
        override val to: Int = from + 1,
    ) : CatalogSchemaMigration {
        override fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode = node
    }

    @Test
    fun `empty chain is valid when baseline equals current`() {
        assertThatCode { validateMigrationChain(emptyList(), baseline = 4, current = 4) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `empty chain is invalid when baseline is below current`() {
        assertThatThrownBy { validateMigrationChain(emptyList(), baseline = 3, current = 4) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("empty")
    }

    @Test
    fun `contiguous chain spanning baseline to current is valid`() {
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 5), NoopMigration(from = 6))
        assertThatCode { validateMigrationChain(chain, baseline = 4, current = 7) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `chain order is irrelevant - sorted internally`() {
        val chain = listOf(NoopMigration(from = 6), NoopMigration(from = 4), NoopMigration(from = 5))
        assertThatCode { validateMigrationChain(chain, baseline = 4, current = 7) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `a gap in the chain fails`() {
        // 4->5, then 6->7: missing 5->6.
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 6))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 7) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Gap")
    }

    @Test
    fun `a duplicate from fails`() {
        val chain = listOf(NoopMigration(from = 4), NoopMigration(from = 4))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 5) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Duplicate")
    }

    @Test
    fun `a chain that does not start at baseline fails`() {
        val chain = listOf(NoopMigration(from = 5))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("baseline")
    }

    @Test
    fun `a chain that does not reach current fails`() {
        val chain = listOf(NoopMigration(from = 4))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("reach current")
    }

    @Test
    fun `a step that advances more than one version fails`() {
        val chain = listOf(NoopMigration(from = 4, to = 6))
        assertThatThrownBy { validateMigrationChain(chain, baseline = 4, current = 6) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("exactly one version")
    }

    @Test
    fun `the real bean accepts the live v3 to v5 chain`() {
        // Constructing the @Component with the wired-in chain must not throw. The
        // catalog window is [3, 5], so both steps (v3→v4, v4→v5) are required.
        assertThatCode {
            CatalogSchemaMigrator(jsonMapper(), listOf(CatalogV3ToV4ExampleMigration(), CatalogV4ToV5Migration()))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `the real bean rejects a missing chain (v3 to v5 required)`() {
        // baseline (3) < current (5) needs both steps — an empty chain, or a
        // partial one, is malformed against the live constants.
        assertThatThrownBy { CatalogSchemaMigrator(jsonMapper(), emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { CatalogSchemaMigrator(jsonMapper(), listOf(CatalogV3ToV4ExampleMigration())) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the real bean rejects a stray migration past current`() {
        // The required v3→v4→v5 chain plus a stray v5→v6 step overshoots current (5).
        assertThatThrownBy {
            CatalogSchemaMigrator(
                jsonMapper(),
                listOf(CatalogV3ToV4ExampleMigration(), CatalogV4ToV5Migration(), NoopMigration(from = 5)),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `chain integrity does not depend on the constants - probes the window directly`() {
        // The checker is parameterised on the version window, so it validates a
        // chain against an arbitrary baseline/current unrelated to today's
        // constants (baseline == current). A contiguous 10->11->12->13 chain over
        // window [10, 13] must pass purely on its own merits.
        val chain = listOf(NoopMigration(from = 10), NoopMigration(from = 11), NoopMigration(from = 12))
        assertThatCode { validateMigrationChain(chain, baseline = 10, current = 13) }
            .doesNotThrowAnyException()
    }
}
