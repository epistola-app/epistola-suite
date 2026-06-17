package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.CatalogPart
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.validateMigrationChain
import app.epistola.suite.catalog.migrations.steps.StencilV1ToV2RequireVersionMigration
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * Startup chain-integrity checks for [CatalogSchemaMigrator] — the contiguous,
 * total, gap-free invariant that fails application start (Flyway-like) when a
 * part's migration chain is malformed. Pure unit test; no Spring.
 */
class CatalogSchemaMigratorChainTest {

    /** A migration step that does nothing but declare its [part]/[from]/[to]. */
    private class NoopMigration(
        override val from: Int,
        override val to: Int = from + 1,
        override val part: CatalogPart = CatalogPart.MANIFEST,
    ) : CatalogSchemaMigration {
        override fun migrate(node: ObjectNode, ctx: MigrationContext): ObjectNode = node
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
    fun `the real bean accepts the live stencil v1 to v2 chain`() {
        // Constructing the @Component with the wired-in chains must not throw. The
        // STENCIL part has baseline 1 / current 2, so its one migration is
        // required; every other part is baseline == current (empty chain).
        assertThatCode { CatalogSchemaMigrator(jsonMapper(), listOf(StencilV1ToV2RequireVersionMigration())) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `the real bean rejects a missing stencil chain`() {
        // STENCIL baseline (1) < current (2) needs its migration — an empty chain
        // is malformed against the live constants.
        assertThatThrownBy { CatalogSchemaMigrator(jsonMapper(), emptyList()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the real bean rejects a stray migration on a complete-chain part`() {
        // A stray manifest step (manifest is baseline == current == 4, no room),
        // on top of the required stencil chain, must fail validation.
        assertThatThrownBy {
            CatalogSchemaMigrator(
                jsonMapper(),
                listOf(StencilV1ToV2RequireVersionMigration(), NoopMigration(from = 4, part = CatalogPart.MANIFEST)),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `chain integrity does not depend on the constants - probes the window directly`() {
        // The checker is parameterised on the version window, so it validates a
        // chain against an arbitrary baseline/current unrelated to today's
        // constants. A contiguous 10->11->12->13 chain over window [10, 13] must
        // pass purely on its own merits.
        val chain = listOf(NoopMigration(from = 10), NoopMigration(from = 11), NoopMigration(from = 12))
        assertThatCode { validateMigrationChain(chain, baseline = 10, current = 13) }
            .doesNotThrowAnyException()
    }
}
