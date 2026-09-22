// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The guarantees that make a move safe to apply: the plan an operator approved is the plan that
 * runs or nothing does, a failure part-way leaves no trace, and a move cannot interleave with
 * another move or with a create that would take the address it vacates.
 */
class RelocationPlanSafetyTest : RelocationTestSupport() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `previewing the same move twice gives the same fingerprint, and changes nothing`() {
        val tenant = tenantWith("Deterministic preview")
        val header = stencilReferencedByDraft(tenant)

        val first = preview(tenant, header.movedTo(shared))
        val second = preview(tenant, header.movedTo(shared))

        assertThat(second.planFingerprint).isEqualTo(first.planFingerprint)
        assertThat(aliases(tenant)).isEmpty()
        assertThat(resolve(tenant, header)!!.canonical).isEqualTo(header)
    }

    @Test
    fun `a version published after the preview makes the plan stale`() {
        val tenant = tenantWith("Stale on publish")
        val header = stencilReferencedByDraft(tenant)
        val plan = preview(tenant, header.movedTo(shared))

        // The draft the plan would rewrite is now an immutable version that must resolve through the alias.
        val variant = invoiceVariant(tenant)
        withMediator { PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute() }

        assertStale(tenant, listOf(header.movedTo(shared)), plan.planFingerprint)
    }

    @Test
    fun `a release cut after the preview makes the plan stale, since its warning was never seen`() {
        val tenant = tenantWith("Stale on release")
        val header = stencilReferencedByDraft(tenant)
        val plan = preview(tenant, header.movedTo(shared))
        assertThat(plan.warnings).isEmpty()

        withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0", publication = ReleasePublication.SKIP).execute() }

        assertStale(tenant, listOf(header.movedTo(shared)), plan.planFingerprint)
    }

    @Test
    fun `a destination taken after the preview is reported stale, not blocked`() {
        val tenant = tenantWith("Stale on occupied target")
        val header = stencilReferencedByDraft(tenant)
        val plan = preview(tenant, header.movedTo(shared))

        withMediator { CreateStencil(StencilId(StencilKey.of("header"), catalogId(tenant, shared)), "Taken").execute() }

        // The operator approved a plan without a blocker; what changed has to be previewed again.
        assertStale(tenant, listOf(header.movedTo(shared)), plan.planFingerprint)
    }

    @Test
    fun `a fingerprint from another plan, or none, is refused`() {
        val tenant = tenantWith("Foreign fingerprint", listOf(letters, shared, archive))
        val header = stencilReferencedByDraft(tenant)
        val otherPlan = preview(tenant, header.movedTo(archive))

        assertStale(tenant, listOf(header.movedTo(shared)), otherPlan.planFingerprint)
        assertStale(tenant, listOf(header.movedTo(shared)), "")
    }

    /**
     * Rewrites, aliases and the first member's move are all written before the second member's
     * update fails. None of it may survive. The failure is injected with a trigger scoped to this
     * test's tenant: no command can make one resource row refuse an update, and the point is a
     * failure after partial writes, which a planner blocker never produces.
     */
    @Test
    fun `a failure part-way through leaves no alias, no rewrite and no moved member`() {
        val tenant = tenantWith("Rollback")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateStencil(StencilId(StencilKey.of("first"), catalogId(tenant, letters)), "First").execute()
            CreateStencil(StencilId(StencilKey.of("second"), catalogId(tenant, letters)), "Second").execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateDraft(variant, templateEmbedding(listOf(Triple("a", "first", letters.value), Triple("b", "second", letters.value)))).execute()
        }
        val draftBefore = withMediator { GetDraft(variant).query()!! }.templateModel
        val batch = listOf(
            address(CatalogResourceType.STENCIL, letters, "first").movedTo(shared),
            address(CatalogResourceType.STENCIL, letters, "second").movedTo(shared),
        )
        val plan = preview(tenant, batch)
        assertThat(plan.executable).isTrue()

        withFailingStencilUpdate(tenant, "second") {
            assertThatThrownBy { withMediator { MoveCatalogResources(tenant, batch, plan.planFingerprint).execute() } }
                .hasMessageContaining("injected failure")
        }

        assertThat(aliases(tenant)).isEmpty()
        assertThat(withMediator { GetDraft(variant).query()!! }.templateModel).isEqualTo(draftBefore)
        batch.forEach { assertThat(resolve(tenant, it.source)!!.canonical).isEqualTo(it.source) }
    }

    /**
     * Two operators previewed moving the same stencil to different catalogs. The second's execute
     * runs while the first's is still open, so it waits on the tenant lock, then re-plans against
     * the committed first move and finds its plan no longer holds.
     */
    @Test
    fun `of two concurrent moves of one resource, exactly one applies`() {
        val tenant = tenantWith("Concurrent moves", listOf(letters, shared, archive))
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        val toShared = listOf(header.movedTo(shared))
        val toArchive = listOf(header.movedTo(archive))
        val sharedPlan = preview(tenant, toShared)
        val archivePlan = preview(tenant, toArchive)

        val contender = whileHeldOpen(
            hold = { MoveCatalogResources(tenant, toShared, sharedPlan.planFingerprint).execute() },
            contend = { MoveCatalogResources(tenant, toArchive, archivePlan.planFingerprint).execute() },
        )

        assertThat(contender.exceptionOrNull()).isInstanceOf(StaleCatalogResourceMovePlanException::class.java)
        assertThat(resolve(tenant, header)!!.canonical.catalogKey).isEqualTo(shared.value)
        assertThat(aliases(tenant)).containsOnlyKeys(header.id)
    }

    /**
     * A create aimed at the address a move is vacating, while that move is still open. It must not
     * end up shadowing the alias the move leaves: every published reference to the address relies
     * on it. Today the create is refused by the address still being occupied when it checks -- it
     * waits on the moving row and then reports a duplicate -- and the alias survives intact.
     */
    @Test
    fun `a create racing a move for the address it vacates is refused, and the alias survives`() {
        val tenant = tenantWith("Create racing move")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        val identity = resolve(tenant, header)!!.resourceId
        val relocation = listOf(header.movedTo(shared))
        val plan = preview(tenant, relocation)

        val contender = whileHeldOpen(
            hold = { MoveCatalogResources(tenant, relocation, plan.planFingerprint).execute() },
            contend = { CreateStencil(StencilId(StencilKey.of("header"), catalogId(tenant, letters)), "Replacement").execute() },
        )

        assertThat(contender.isFailure).describedAs("the racing create must be refused").isTrue()
        val resolved = resolve(tenant, header)!!
        assertThat(resolved.resolvedViaAlias).isTrue()
        assertThat(resolved.resourceId).isEqualTo(identity)
    }

    /**
     * Runs [hold] in a transaction held open, then [contend] on another thread, and releases the
     * holder only once the database reports the contender blocked behind it -- so the overlap is
     * real rather than two operations that happened to run one after the other.
     */
    private fun whileHeldOpen(hold: () -> Unit, contend: () -> Unit): Result<Unit> {
        val holderPid = java.util.concurrent.atomic.AtomicInteger()
        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val holder = threads.submit {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        hold()
                        holderPid.set(jdbi.withHandle<Int, Exception> { it.createQuery("SELECT pg_backend_pid()").mapTo(Int::class.java).one() })
                        holderReady.countDown()
                        releaseHolder.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
            assertThat(holderReady.await(HOLD_SECONDS, TimeUnit.SECONDS)).describedAs("holder ready").isTrue()

            val contender = threads.submit<Result<Unit>> { withMediator { runCatching { contend() } } }
            awaitBlockedBehind(holderPid.get())
            releaseHolder.countDown()

            holder.get(HOLD_SECONDS, TimeUnit.SECONDS)
            return contender.get(HOLD_SECONDS, TimeUnit.SECONDS)
        } finally {
            releaseHolder.countDown()
            threads.shutdownNow()
        }
    }

    /** Polls until some session is waiting on a lock [pid] holds. A condition, not a sleep. */
    private fun awaitBlockedBehind(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_SECONDS)
        while (System.nanoTime() < deadline) {
            val blocked = jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid)))")
                    .bind("pid", pid)
                    .mapTo(Boolean::class.java)
                    .one()
            }
            if (blocked) return
            Thread.onSpinWait()
        }
        throw AssertionError("the contender never blocked behind the held transaction")
    }

    /** Makes updating stencil [key] in [tenant] fail for the duration of [block]. */
    private fun withFailingStencilUpdate(tenant: TenantKey, key: String, block: () -> Unit) {
        val name = "fail_" + UUID.randomUUID().toString().replace("-", "")
        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF OLD.tenant_key = '${tenant.value}' AND OLD.id = '$key' THEN
                        RAISE EXCEPTION 'injected failure';
                    END IF;
                    RETURN NEW;
                END ${'$'}${'$'}
                """,
            )
            handle.execute("CREATE TRIGGER $name BEFORE UPDATE ON stencils FOR EACH ROW EXECUTE FUNCTION $name()")
        }
        try {
            block()
        } finally {
            jdbi.useHandle<Exception> { handle ->
                handle.execute("DROP TRIGGER $name ON stencils")
                handle.execute("DROP FUNCTION $name()")
            }
        }
    }

    private fun invoiceVariant(tenant: TenantKey) = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters)))

    /** Stencil `letters/header`, published, and inserted by the draft of template `letters/invoice`. */
    private fun stencilReferencedByDraft(tenant: TenantKey) = address(CatalogResourceType.STENCIL, letters, "header").also {
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        withMediator {
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            CreateDocumentTemplate(invoiceVariant(tenant).templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(invoiceVariant(tenant), templateEmbedding("header", letters.value)).execute()
        }
    }

    private fun assertStale(tenant: TenantKey, relocations: List<ResourceRelocation>, fingerprint: String) {
        val aliasesBefore = aliases(tenant)
        assertThatThrownBy { withMediator { MoveCatalogResources(tenant, relocations, fingerprint).execute() } }
            .isInstanceOf(StaleCatalogResourceMovePlanException::class.java)
        assertThat(aliases(tenant)).isEqualTo(aliasesBefore)
    }

    private companion object {
        const val HOLD_SECONDS = 30L
    }
}
