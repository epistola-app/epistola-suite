// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.testing.FakeSupportRequest
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The reconcile against a stand-in support service over real HTTP (`docs/support-protocol.md`,
 * "Catalog mirror"): complete sets, uploads of exactly the releases the mirror lacks, withdrawals,
 * the digest skip, back-off and "Sync now".
 *
 * The database is shared with other test classes, whose tenants may also be reconciled here; every
 * assertion is about this test's own tenants.
 */
class CatalogMirrorReconcilerIntegrationTest : CatalogMirrorTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var supportStore: SupportConnectionStore

    @Autowired
    private lateinit var reconciler: CatalogMirrorReconciler

    @Autowired
    private lateinit var scheduler: CatalogMirrorReconcileScheduler

    @Autowired
    private lateinit var mirrorStore: CatalogMirrorStateStore

    @Autowired
    private lateinit var mirrorClient: CatalogMirrorClient

    @Autowired
    private lateinit var supportProperties: app.epistola.suite.support.SupportProperties

    @Autowired
    private lateinit var mirrorProperties: CatalogMirrorProperties

    @Autowired
    private lateinit var work: app.epistola.suite.support.connection.SupportBackgroundWork

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @BeforeEach
    fun connect() {
        mirror.reset()
        supportStore.forget()
        connection.invalidate()
        connect(setOf("mirror"))
    }

    private fun connect(scopes: Set<String>) {
        connection.checkNow()
        support.activate(scopes = scopes)
        // A token fetched before the grant carries no scope; the next request fetches one that does.
        support.expireTokens()
        connection.checkNow()
        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
    }

    private fun archive(catalog: CatalogId, version: String): ByteArray = withMediator { ExportCatalogZip(catalog, version).execute().zipBytes }

    @Autowired
    private lateinit var syncTimer: CatalogMirrorSyncTimerHandler

    @Test
    fun `a Sync now that finds the tenant claimed by another round asks again rather than being lost`() {
        val tenant = createTenant("Mirror Sync Busy").id
        optIn(tenant, shareByDefault = true)
        val otherRound = checkNotNull(mirrorStore.claim(tenant))
        try {
            val result = withMediator { syncTimer.sync(tenant) }

            assertThat(result).isEqualTo(TenantReconcileResult.BUSY)
            val timers = jdbi.withHandle<Int, Exception> {
                it.createQuery("SELECT COUNT(*) FROM cluster_timers WHERE timer_key = :k").bind("k", CatalogMirrorSyncTimerHandler.timerKey(tenant)).mapTo(Int::class.java).one()
            }
            assertThat(timers).`as`("a timer runs it again").isEqualTo(1)
        } finally {
            mirrorStore.release(tenant, otherRound)
        }
    }

    @Test
    fun `while tenants are confidential, Sync now sends no tenant key to the mirror`() {
        val tenant = createTenant("Mirror Confidential Sync").id
        withMediator { app.epistola.suite.support.privacy.SetSupportPrivacy(app.epistola.suite.support.privacy.TenantIdentity.CONFIDENTIAL, app.epistola.suite.support.privacy.TelemetryDetail.PSEUDONYMISED).execute() }
        try {
            val before = support.requests.count { it.method == "PUT" && it.path.startsWith("/api/v1/mirror/tenants/") }

            reconciler.syncTenant(tenant)

            assertThat(support.requests.filter { it.method == "PUT" && it.path.startsWith("/api/v1/mirror/tenants/") }.drop(before).map { it.path })
                .`as`("no request names %s", tenant.value).isEmpty()
        } finally {
            withMediator { app.epistola.suite.support.privacy.SetSupportPrivacy(app.epistola.suite.support.privacy.TenantIdentity.NAMED, app.epistola.suite.support.privacy.TelemetryDetail.PSEUDONYMISED).execute() }
        }
    }

    @Test
    fun `without the mirror scope the reconcile calls nothing`() {
        supportStore.forget()
        connection.invalidate()
        connect(setOf("requests"))
        val tenant = createTenant("Mirror no scope").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        val before = support.requests.size

        val report = reconciler.reconcileAll()

        assertThat(report.ran).isFalse()
        assertThat(support.requests.drop(before).filter { it.path.startsWith("/api/v1/mirror") }).isEmpty()
    }

    @Test
    fun `a mirror scope Epistola withdraws stops the round without failing it, and nothing is called until it is granted again`() {
        val tenant = createTenant("Mirror withdrawn scope").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        // Staff withdraw the mirror: the service refuses the scope at once, with the token still valid.
        support.activate(scopes = setOf("requests"))
        val before = support.requests.size

        val refused = reconciler.reconcileAll()
        val after = reconciler.reconcileAll()

        assertThat(refused.ran).isFalse()
        assertThat(after.ran).isFalse()
        assertThat(support.requests.drop(before).filter { it.path.startsWith("/api/v1/mirror") }).`as`("one refused call, then none").hasSize(1)
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()
        assertThat(settings(tenant).round!!.consecutiveFailures).isZero()

        support.activate(scopes = setOf("mirror"))
        connection.checkNow()
        assertThat(reconciler.syncTenant(tenant)).isEqualTo(TenantReconcileResult.SENT)
    }

    @Test
    fun `a withdrawal refused for the mirror scope stays pending and counts no failure`() {
        val tenant = createTenant("Mirror withdrawal refused").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        withMediator { OptOutCatalogMirror(tenant).execute() }
        mirror.refuseScopeOnSet = true

        val report = reconciler.reconcileAll()

        // Other test classes' sharing tenants may come first; the first refusal stops the round either way.
        assertThat(report.tenants.values).contains(TenantReconcileResult.NOT_GRANTED).doesNotContain(TenantReconcileResult.FAILED)
        assertThat(report.tenants[tenant.value]).isIn(null, TenantReconcileResult.NOT_GRANTED)
        assertThat(settings(tenant).consent!!.withdrawalPending).isTrue()
        assertThat(settings(tenant).round!!.consecutiveFailures).isZero()
        assertThat(mirror.held).`as`("still held, to be withdrawn later").containsKey(tenant.value)
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()

        mirror.refuseScopeOnSet = false
        connection.checkNow()
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(settings(tenant).consent!!.withdrawalPending).isFalse()
    }

    @Test
    fun `a round sends the complete set and uploads exactly the releases the mirror lacks, as the suite exports them`() {
        val tenant = createTenant("Mirror round").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0")
        val secret = releasedCatalog(tenant, "secret", "1.0.0")
        optInOwnCatalogs(tenant)
        policy(secret, CatalogMirrorPolicy.NEVER)
        val hashes = contentHashes(letters)
        // The mirror already has 1.0.0, from another tenant of this installation.
        mirror.stored[hashes.getValue("1.0.0")] = byteArrayOf(1)

        val report = reconciler.reconcileAll()

        assertThat(report.tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        val sent = mirror.lastSet(tenant)
        assertThat(sent.map { it.key to it.version }).containsExactlyInAnyOrder("letters" to "1.0.0", "letters" to "1.1.0")
        assertThat(sent.map { it.contentHash }).doesNotContainAnyElementsOf(contentHashes(secret).values)
        assertThat(mirror.uploadsOf(hashes.values)).containsExactly(hashes.getValue("1.1.0"))
        val uploaded = mirror.stored.getValue(hashes.getValue("1.1.0"))
        assertThat(uploaded).startsWith(*"PK".toByteArray()).isEqualTo(archive(letters, "1.1.0"))
        assertThat(mirror.uploadTypes).containsOnly("application/zip")
        assertThat(mirror.uploads).doesNotContainAnyElementsOf(contentHashes(secret).values)

        val round = settings(tenant).round!!
        assertThat(round.bindingsSent).isEqualTo(2)
        assertThat(round.releasesUploaded).isEqualTo(1)
        assertThat(round.releasesMissing).isZero()
        assertThat(round.lastError).isNull()
        assertThat(settings(tenant).catalogs.single { it.catalog == letters }.lastSyncedAt).isEqualTo(round.lastSuccessAt)
    }

    @Test
    fun `an unchanged set is not sent again until a day has passed, a new release is`() {
        val tenant = createTenant("Mirror digest").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        val puts = mirror.puts(tenant)

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.UNCHANGED)
        assertThat(mirror.puts(tenant)).isEqualTo(puts)

        testClock.advanceBy(Duration.ofHours(25))
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).`as`("at least daily").isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.uploadsOf(contentHashes(letters).values)).`as`("nothing new to upload").hasSize(1)

        releaseDependingOnNothing(letters, "1.1.0")
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.lastSet(tenant).map { it.version }).containsExactlyInAnyOrder("1.0.0", "1.1.0")
        assertThat(mirror.uploadsOf(contentHashes(letters).values)).hasSize(2)
    }

    @Test
    fun `tenants the mirror holds but that do not share are withdrawn, and an opt-out is confirmed`() {
        val tenant = createTenant("Mirror withdraw").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        assertThat(mirror.held.getValue(tenant.value)).hasSize(1)
        // A tenant deleted from this installation that the mirror still holds.
        mirror.held["mirror-gone-tenant"] = setOf("v6:${"a".repeat(64)}")
        withMediator { OptOutCatalogMirror(tenant).execute() }

        val report = reconciler.reconcileAll()

        assertThat(report.tenants[tenant.value]).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(report.tenants["mirror-gone-tenant"]).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(mirror.lastSet(tenant)).isEmpty()
        assertThat(mirror.held).doesNotContainKeys(tenant.value, "mirror-gone-tenant")
        val consent = settings(tenant).consent!!
        assertThat(consent.withdrawalPending).isFalse()
        assertThat(consent.withdrawalConfirmedAt).isNotNull()

        val putsAfter = mirror.puts(tenant)
        reconciler.reconcileAll()
        assertThat(mirror.puts(tenant)).`as`("a confirmed withdrawal is not repeated").isEqualTo(putsAfter)
    }

    @Test
    fun `an unavailable service backs the tenant off, and Sync now goes ahead regardless`() {
        val tenant = createTenant("Mirror backoff").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        mirror.failTenant = tenant.value

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.UNAVAILABLE)

        val round = settings(tenant).round!!
        assertThat(round.consecutiveFailures).isEqualTo(1)
        assertThat(round.lastError).contains("503")
        assertThat(round.nextAttemptAt).isEqualTo(round.lastAttemptAt!!.plus(Duration.ofMinutes(15)))
        mirror.failTenant = null
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).`as`("backing off").isEqualTo(TenantReconcileResult.SKIPPED)

        withMediator { RequestCatalogMirrorSync(tenant).execute() }
        assertThat(settings(tenant).round!!.syncRequestedAt).isNotNull()
        scheduling.runDue()
        // The timer hands the sync to the support background work.
        work.awaitIdle()

        assertThat(mirror.lastSet(tenant)).hasSize(1)
        val after = settings(tenant).round!!
        assertThat(after.consecutiveFailures).isZero()
        assertThat(after.syncRequestedAt).isNull()
        assertThat(after.lastError).isNull()
    }

    @Test
    fun `a release the mirror refuses is recorded and the round goes on`() {
        val tenant = createTenant("Mirror refused").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0")
        optInOwnCatalogs(tenant)
        val refused = contentHashes(letters).getValue("1.0.0")
        mirror.refuseRelease = refused

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)

        val round = settings(tenant).round!!
        assertThat(mirror.uploadsOf(contentHashes(letters).values)).containsExactly(contentHashes(letters).getValue("1.1.0"))
        assertThat(round.releasesMissing).isEqualTo(1)
        assertThat(round.failedReleases.single().contentHash).isEqualTo(refused)
        assertThat(round.failedReleases.single().error).contains("413")
        mirror.refuseRelease = null
        // The refused release waits for its own back-off (one interval after one refusal), then goes again.
        testClock.advanceBy(mirrorProperties.interval.plusMinutes(1))
        assertThat(reconciler.reconcileAll().tenants[tenant.value]).`as`("not skipped while a release is missing").isEqualTo(TenantReconcileResult.SENT)
        assertThat(settings(tenant).round!!.releasesMissing).isZero()
    }

    @Test
    fun `an opt-out while another round holds the tenant sends nothing until that round ended, then withdraws it`() {
        val tenant = createTenant("Mirror opt-out busy").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        val puts = mirror.puts(tenant)
        // Another node's round is sending the tenant's set right now.
        val claim = mirrorStore.claim(tenant)
        assertThat(claim).isNotNull()

        withMediator { OptOutCatalogMirror(tenant).execute() }
        scheduling.runDue()
        work.awaitIdle()

        assertThat(mirror.puts(tenant)).`as`("no unclaimed withdrawal that the running round could overtake").isEqualTo(puts)
        assertThat(settings(tenant).consent!!.withdrawalPending).isTrue()

        mirrorStore.release(tenant, claim!!)
        scheduling.advanceTimeBy(Duration.ofMinutes(2))
        work.awaitIdle()

        assertThat(mirror.lastSet(tenant)).isEmpty()
        assertThat(mirror.held).doesNotContainKey(tenant.value)
        assertThat(settings(tenant).consent!!.withdrawalConfirmedAt).isNotNull()
    }

    @Test
    fun `an opt-out during a round stops its uploads, and the round withdraws what it sent`() {
        val tenant = createTenant("Mirror opt-out mid-round").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0", "1.2.0")
        optInOwnCatalogs(tenant)
        val hashes = contentHashes(letters).values.toSet()
        // Other test classes' tenants are reconciled in the same round: only this tenant's first upload counts.
        mirror.onUpload = { hash -> if (hash in hashes) withMediator { OptOutCatalogMirror(tenant).execute() } }

        val result = reconciler.reconcileAll().tenants[tenant.value]

        assertThat(mirror.uploadsOf(contentHashes(letters).values)).`as`("nothing after the opt-out").hasSize(1)
        assertThat(result).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(mirror.lastSet(tenant)).isEmpty()
        assertThat(mirror.held).doesNotContainKey(tenant.value)
        assertThat(settings(tenant).consent!!.withdrawalConfirmedAt).isNotNull()
    }

    /** The release uploads of [hashes] that reached the stand-in service, refused ones included. */
    private fun releaseRequests(hashes: Collection<String>) = support.requests.filter { request ->
        request.method == "PUT" && request.path.startsWith("/api/v1/mirror/releases/") && hashes.any { request.path.endsWith(it) }
    }

    @Test
    fun `a catalog marked never to be shared during a round has nothing more uploaded`() {
        val tenant = createTenant("Mirror withhold mid-round").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0", "1.2.0")
        optInOwnCatalogs(tenant)
        val hashes = contentHashes(letters).values.toSet()
        // A catalog manager withholds the catalog while its first release is being uploaded.
        mirror.onUpload = { hash -> if (hash in hashes) policy(letters, CatalogMirrorPolicy.NEVER) }

        reconciler.reconcileAll()

        assertThat(releaseRequests(hashes)).`as`("nothing of it after it was withheld").hasSize(1)
    }

    @Test
    fun `the mirror deselected during a round uploads nothing more`() {
        val tenant = createTenant("Mirror deselect mid-round").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0", "1.2.0")
        optInOwnCatalogs(tenant)
        val hashes = contentHashes(letters).values.toSet()
        // An installation administrator deselects the mirror while the first release is being uploaded.
        mirror.onUpload = { hash -> if (hash in hashes) connection.saveSelectedServices(listOf("requests"), "test") }
        try {
            reconciler.reconcileAll()
        } finally {
            supportStore.forgetServices()
            connection.invalidate()
        }

        assertThat(releaseRequests(hashes)).`as`("nothing sent after the deselection").hasSize(1)
    }

    @Test
    fun `an unchanged set is sent again when the mirror no longer holds the tenant`() {
        val tenant = createTenant("Mirror lost").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        // The mirror lost the tenant: restored from a backup, or an earlier withdrawal overtook a set.
        mirror.held.remove(tenant.value)

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.held.getValue(tenant.value)).hasSize(1)
    }

    @Test
    fun `an answer that does not state bindings confirms no withdrawal`() {
        val tenant = createTenant("Mirror blank answer").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        reconciler.reconcileAll()
        withMediator { OptOutCatalogMirror(tenant).execute() }
        mirror.blankAnswers = true

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.FAILED)

        assertThat(settings(tenant).consent!!.withdrawalPending).isTrue()
        assertThat(settings(tenant).consent!!.withdrawalConfirmedAt).isNull()
    }

    @Test
    fun `with the mirror turned off nothing is sent, but tenants that stopped sharing are still withdrawn`() {
        val stopped = createTenant("Mirror off opt-out").id
        val sharing = createTenant("Mirror off sharing").id
        releasedCatalog(stopped, "letters", "1.0.0")
        releasedCatalog(sharing, "letters", "1.0.0")
        optInOwnCatalogs(stopped)
        reconciler.reconcileAll()
        optInOwnCatalogs(sharing)
        val off = CatalogMirrorReconciler(connection, supportProperties, mirrorProperties.copy(enabled = false), mirrorClient, mirrorStore, mediator, privacy)
        withMediator { OptOutCatalogMirror(stopped).execute() }
        // The opt-out's own "Sync now" is left to the reconciler with the mirror off.
        val report = off.reconcileAll()

        assertThat(report.tenants[stopped.value]).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(mirror.held).doesNotContainKey(stopped.value)
        assertThat(settings(stopped).consent!!.withdrawalConfirmedAt).isNotNull()
        assertThat(report.tenants).doesNotContainKey(sharing.value)
        assertThat(mirror.puts(sharing)).isZero()
        assertThat(off.syncTenant(sharing)).isEqualTo(TenantReconcileResult.SKIPPED)
        assertThat(mirror.puts(sharing)).isZero()
    }

    @Test
    fun `one node reconciles on the configured interval`() {
        val definition = scheduler.catalogMirrorReconcileTaskDefinition()
        assertThat(definition.executionScope).isEqualTo(ClusterScheduledTaskExecutionScope.SINGLE_OWNER)
        assertThat(definition.schedule).isEqualTo(ClusterScheduledTaskSchedule.FixedDelay(Duration.ofMinutes(15).toMillis()))
        assertThat(definition.taskKey).isEqualTo("support.mirror.reconcile")
    }

    /** Releases [catalog]'s next version with the content it has (a release with nothing new). */
    private fun releaseDependingOnNothing(catalog: CatalogId, version: String) = withMediator {
        app.epistola.suite.themes.commands.UpdateTheme(
            app.epistola.suite.common.ids.ThemeId(app.epistola.suite.common.ids.ThemeKey.of("brand"), catalog),
            documentStyles = mapOf("color" to "#123456"),
            catalogVersion = null,
        ).execute()
        app.epistola.suite.catalog.commands.MarkCatalogReady(catalog, catalogVersion = null).execute()
        app.epistola.suite.catalog.commands.ReleaseCatalogDraft(catalog, version, catalogVersion = null).execute()
    }

    // --- reliability: a slow or refusing service (docs/support-protocol.md, "Reliability") ---

    @Test
    fun `a round keeps to its time budget and the next round uploads the rest`() {
        val tenant = createTenant("Mirror budget").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1", "1.0.2", "1.0.3", "1.0.4")
        val mine = contentHashes(catalog).values.toSet()
        mirror.onUpload = { if (it in mine) Thread.sleep(1_000) }
        optInOwnCatalogs(tenant)

        val took = kotlin.system.measureTimeMillis { reconciler.reconcileAll() }

        assertThat(took).isLessThan(4_500)
        assertThat(mirror.uploadsOf(mine).size).isLessThan(mine.size)
        mirror.onUpload = null
        reconciler.reconcileAll()
        assertThat(mirror.uploadsOf(mine)).containsExactlyInAnyOrderElementsOf(mine)
    }

    @Test
    fun `the scheduled round runs off the scheduler thread`() {
        val tenant = createTenant("Mirror off thread").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1")
        val mine = contentHashes(catalog).values.toSet()
        mirror.onUpload = { if (it in mine) Thread.sleep(1_000) }
        optInOwnCatalogs(tenant)

        val took = kotlin.system.measureTimeMillis { scheduler.handle(app.epistola.suite.testing.dueScheduledTask(scheduler.catalogMirrorReconcileTaskDefinition())) }

        assertThat(took).isLessThan(500)
        assertThat(work.awaitIdle(java.time.Duration.ofSeconds(30))).isTrue()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["507:quota_exceeded", "422:unsupported_hash"])
    fun `a refusal that holds for every release stops the uploads and is not repeated every round`(answer: String) {
        val (status, error) = answer.split(':')
        val tenant = createTenant("Mirror systemic $status").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1", "1.0.2")
        val mine = contentHashes(catalog).values.toSet()
        val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
        mirror.onUpload = { if (it in mine) attempts += it }
        mirror.releaseAnswer = { if (it in mine) FakeSupportResponse.json(status.toInt(), """{"error":"$error"}""") else null }
        optInOwnCatalogs(tenant)

        reconciler.reconcileAll()
        assertThat(attempts).`as`("stopped after the first").hasSize(1)

        testClock.advanceBy(mirrorProperties.interval.plusMinutes(1))
        reconciler.reconcileAll()
        assertThat(attempts).`as`("backing off").hasSize(1)

        testClock.advanceBy(java.time.Duration.ofHours(7))
        mirror.releaseAnswer = null
        reconciler.reconcileAll()
        assertThat(mirror.uploadsOf(mine)).`as`("tried again later, and taken").containsExactlyInAnyOrderElementsOf(mine)
    }

    @Test
    fun `a release refused for a hash mismatch fails alone, waits, and is retried in a later round`() {
        val tenant = createTenant("Mirror hash mismatch").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1", "1.0.2")
        val hashes = contentHashes(catalog)
        val bad = hashes.getValue("1.0.1")
        val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
        mirror.onUpload = { if (it == bad) attempts += it }
        mirror.releaseAnswer = { if (it == bad) FakeSupportResponse.json(422, """{"error":"hash_mismatch"}""") else null }
        optInOwnCatalogs(tenant)

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.uploadsOf(hashes.values)).containsExactlyInAnyOrder(hashes.getValue("1.0.0"), hashes.getValue("1.0.2"))
        assertThat(settings(tenant).round!!.failedReleases.map { it.contentHash }).containsExactly(bad)

        reconciler.reconcileAll()
        assertThat(attempts).`as`("not again before its back-off").hasSize(1)

        testClock.advanceBy(mirrorProperties.interval.plusMinutes(1))
        mirror.releaseAnswer = null
        reconciler.reconcileAll()
        assertThat(mirror.uploadsOf(hashes.values)).contains(bad)
        assertThat(settings(tenant).round!!.failedReleases).isEmpty()
    }

    @Test
    fun `a server error on one release fails that release alone, and the round goes on`() {
        val tenant = createTenant("Mirror release 500").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1", "1.0.2")
        val hashes = contentHashes(catalog)
        val bad = hashes.getValue("1.0.0")
        val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
        mirror.onUpload = { if (it == bad) attempts += it }
        mirror.releaseAnswer = { if (it == bad) FakeSupportResponse.json(500, """{"error":"server_error"}""") else null }
        optInOwnCatalogs(tenant)

        assertThat(reconciler.reconcileAll().tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.uploadsOf(hashes.values)).containsExactlyInAnyOrder(hashes.getValue("1.0.1"), hashes.getValue("1.0.2"))
        assertThat(settings(tenant).round!!.failedReleases.map { it.contentHash }).containsExactly(bad)

        reconciler.reconcileAll()
        assertThat(attempts).`as`("waits for its own back-off").hasSize(1)

        testClock.advanceBy(mirrorProperties.interval.plusMinutes(1))
        mirror.releaseAnswer = null
        reconciler.reconcileAll()
        assertThat(mirror.uploadsOf(hashes.values)).contains(bad)
    }

    @Test
    fun `a registration refused during a round stops it, blames no tenant or release, and asks for no more tokens`() {
        val first = createTenant("Mirror refused registration A").id
        val second = createTenant("Mirror refused registration B").id
        val catalog = releasedCatalog(first, "letters", "1.0.0", "1.0.1", "1.0.2")
        releasedCatalog(second, "letters", "1.0.0", "1.0.1")
        val mine = contentHashes(catalog).values.toSet()
        fun tokenRequests() = support.requests.count { it.method == "POST" && it.path == "/oauth2/token" }
        val tokensAtRevocation = java.util.concurrent.atomic.AtomicInteger(-1)
        // Staff revoke the registration while the first tenant's releases go up.
        mirror.onUpload = {
            if (it in mine && tokensAtRevocation.compareAndSet(-1, tokenRequests())) support.revoke()
        }
        optInOwnCatalogs(first)
        optInOwnCatalogs(second)

        reconciler.reconcileAll()

        assertThat(connection.status().state).isInstanceOf(SupportConnectionState.Refused::class.java)
        // One token request finds the refusal, and one more confirms it: the fake's Date header is the
        // real time, months from the test clock, so the refusal could have been about this node's clock.
        assertThat(tokenRequests() - tokensAtRevocation.get()).`as`("one token request finds the refusal, one confirms it").isEqualTo(2)
        for (tenant in listOf(first, second)) {
            val round = settings(tenant).round
            assertThat(round?.lastError).`as`("tenant %s", tenant.value).isNull()
            assertThat(round?.failedReleases.orEmpty()).`as`("releases of %s", tenant.value).isEmpty()
        }
    }

    @Test
    fun `a round whose claim another round took over stops instead of uploading alongside it`() {
        val tenant = createTenant("Mirror claim").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0", "1.0.1")
        val hashes = contentHashes(catalog).values.toList()
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.atomic.AtomicReference<String?>(null)
        mirror.onUpload = { hash ->
            if (hash in hashes && blocked.compareAndSet(null, hash)) {
                entered.countDown()
                proceed.await(20, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        optInOwnCatalogs(tenant)
        val clock = app.epistola.suite.time.EpistolaClock.capture()
        val round = java.util.concurrent.CompletableFuture.runAsync { app.epistola.suite.time.EpistolaClock.withClock(clock) { reconciler.reconcileAll() } }
        assertThat(entered.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

        // The first round's claim is older than the lease: "Sync now" takes the tenant over and finishes.
        claimLeaseRunsOut(tenant)
        reconciler.syncTenant(tenant)
        proceed.countDown()
        round.join()

        val other = hashes.single { it != blocked.get() }
        assertThat(mirror.uploadsOf(listOf(other))).`as`("uploaded by the round that holds the tenant, not again by the one that lost it").hasSize(1)
    }

    /**
     * A node killed mid-round leaves its claim behind. Seen live with two nodes: the other node
     * skipped the tenant for half an hour, and a release made meanwhile was not mirrored.
     */
    @Test
    fun `a tenant claimed by a round on a node that died is taken over after five minutes`() {
        val tenant = createTenant("Mirror dead node").id
        val catalog = releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        // The dead node's round: it took the claim and never let go.
        assertThat(mirrorStore.claim(tenant)).isNotNull()
        assertThat(reconciler.syncTenant(tenant)).`as`("while the round may still be running").isEqualTo(TenantReconcileResult.BUSY)

        claimLeaseRunsOut(tenant)

        assertThat(reconciler.isClaimed(tenant)).isFalse()
        assertThat(reconciler.syncTenant(tenant)).isEqualTo(TenantReconcileResult.SENT)
        assertThat(mirror.uploadsOf(contentHashes(catalog).values.toList())).hasSize(1)
    }

    /**
     * The lease is measured by the database's clock: a node whose own clock runs ahead (or behind)
     * by more than the lease does not take over a round another node is running.
     */
    @Test
    fun `a node whose clock is ahead by more than the lease does not take over a live round`() {
        val tenant = createTenant("Mirror skewed node").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        // Another node's round, running now.
        assertThat(mirrorStore.claim(tenant)).isNotNull()

        testClock.advanceBy(mirrorStore.claimLease.plusMinutes(1)) // this node's clock is ahead

        assertThat(reconciler.isClaimed(tenant)).isTrue()
        assertThat(reconciler.syncTenant(tenant)).isEqualTo(TenantReconcileResult.BUSY)
    }

    /**
     * Ages [tenant]'s claim past the lease. Raw SQL: the lease runs on the database's clock, which a
     * test cannot move, and no command backdates a claim.
     */
    private fun claimLeaseRunsOut(tenant: TenantKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE support_mirror_state SET claimed_at = claimed_at - make_interval(secs => :s) WHERE tenant_key = :t")
                .bind("s", mirrorStore.claimLease.seconds + 1)
                .bind("t", tenant.value)
                .execute()
        }
    }

    /** A binding as the stand-in received it. */
    data class SentBinding(val key: String, val version: String, val contentHash: String)

    /** The mirror endpoints of the stand-in support service: what it holds and what it was sent. */
    class FakeMirror {
        val held = ConcurrentHashMap<String, Set<String>>()
        val stored = ConcurrentHashMap<String, ByteArray>()
        val sets = CopyOnWriteArrayList<Pair<String, List<SentBinding>>>()
        val uploads = CopyOnWriteArrayList<String>()
        val uploadTypes = CopyOnWriteArrayList<String?>()

        @Volatile var failTenant: String? = null

        @Volatile var refuseRelease: String? = null

        /** Answers every set with 200 and an empty body, as a misbehaving proxy might. */
        @Volatile var blankAnswers = false

        /** Runs before an upload is taken in: what happens on the installation meanwhile. */
        @Volatile var onUpload: ((String) -> Unit)? = null

        /** Answers a set with `403 insufficient_scope`, as the service does the moment staff withdraw the mirror. */
        @Volatile var refuseScopeOnSet = false

        /** Answers an upload instead, when it returns non-null: a refusal for some releases. */
        @Volatile var releaseAnswer: ((String) -> FakeSupportResponse?)? = null

        fun reset() {
            held.clear()
            stored.clear()
            sets.clear()
            uploads.clear()
            uploadTypes.clear()
            failTenant = null
            refuseRelease = null
            blankAnswers = false
            onUpload = null
            refuseScopeOnSet = false
            releaseAnswer = null
        }

        /** The uploads among [hashes]: other test classes' tenants may be reconciled in the same round. */
        fun uploadsOf(hashes: Collection<String>): List<String> = uploads.filter { it in hashes }

        fun puts(tenant: TenantKey): Int = sets.count { it.first == tenant.value }

        fun lastSet(tenant: TenantKey): List<SentBinding> = sets.last { it.first == tenant.value }.second

        fun install(support: FakeSupportServer) {
            support.route("GET", "/api/v1/mirror/tenants") { r ->
                scoped(r) { FakeSupportResponse.json(200, """{"tenants":[${held.keys.joinToString(",") { "\"$it\"" }}]}""") }
            }
            support.route("PUT", "/api/v1/mirror/tenants/") { r -> scoped(r) { putTenant(r) } }
            support.route("PUT", "/api/v1/mirror/releases/") { r -> scoped(r) { putRelease(r) } }
        }

        private fun scoped(r: FakeSupportRequest, handler: () -> FakeSupportResponse): FakeSupportResponse = when {
            r.clientId == null -> FakeSupportResponse.invalidToken()
            "mirror" !in r.tokenScopes -> FakeSupportResponse.insufficientScope("mirror")
            else -> handler()
        }

        private fun putTenant(r: FakeSupportRequest): FakeSupportResponse {
            if (refuseScopeOnSet) return FakeSupportResponse.insufficientScope("mirror")
            val tenant = r.path.removePrefix("/api/v1/mirror/tenants/")
            if (tenant == failTenant) return FakeSupportResponse.json(503, """{"error":"unavailable"}""")
            val bindings = r.json().path("bindings").iterator().asSequence().map {
                SentBinding(it.path("key").asString(), it.path("version").asString(), it.path("contentHash").asString())
            }.toList()
            sets += tenant to bindings
            if (blankAnswers) return FakeSupportResponse.empty(200)
            val removed = held[tenant].orEmpty().size
            if (bindings.isEmpty()) held.remove(tenant) else held[tenant] = bindings.map { it.contentHash }.toSet()
            val missing = bindings.map { it.contentHash }.distinct().filter { !stored.containsKey(it) }
            return FakeSupportResponse.json(
                200,
                """{"missing":[${missing.joinToString(",") { "\"$it\"" }}],"bindings":${bindings.size},"removed":$removed}""",
            )
        }

        private fun putRelease(r: FakeSupportRequest): FakeSupportResponse {
            val hash = r.path.removePrefix("/api/v1/mirror/releases/")
            onUpload?.invoke(hash)
            if (held.values.none { hash in it }) return FakeSupportResponse.json(409, """{"error":"not_announced"}""")
            if (hash == refuseRelease) return FakeSupportResponse.json(413, """{"error":"too_large"}""")
            releaseAnswer?.invoke(hash)?.let { return it }
            val created = stored.put(hash, r.body) == null
            uploads += hash
            uploadTypes += r.header("Content-Type")
            return FakeSupportResponse.empty(if (created) 201 else 200)
        }
    }

    companion object {
        private val support = FakeSupportServer()
        private val mirror = FakeMirror().also { it.install(support) }

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            // Every service selected, as an administrator would; the selection itself is tested in SupportServiceSelectionIntegrationTest.
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            // These tests let the check register; without it, an installation administrator registers.
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.mirror.round-budget") { "3s" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
