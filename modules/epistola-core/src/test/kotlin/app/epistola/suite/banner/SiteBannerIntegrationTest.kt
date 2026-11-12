// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.banner

import app.epistola.suite.banner.commands.ClearSiteBanner
import app.epistola.suite.banner.commands.SeedSiteBannerIfAbsent
import app.epistola.suite.banner.commands.SetSiteBanner
import app.epistola.suite.banner.queries.GetSiteBanner
import app.epistola.suite.banner.queries.ResolveSiteBanner
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The site banner is an installation-wide singleton (a single `app_metadata` row),
 * so — unlike per-tenant fixtures — it is shared across tests. Each test starts from
 * a clean slate: the row is deleted and the store cache invalidated in [reset].
 */
class SiteBannerIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var store: SiteBannerStore

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun reset() {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", "site_banner").execute()
        }
        store.invalidate()
    }

    @Test
    fun `set then get round-trips the banner`(): Unit = withMediator {
        SetSiteBanner("Maintenance tonight", SiteBannerSeverity.WARNING, enabled = true).execute()

        val banner = GetSiteBanner().query()
        assertThat(banner).isNotNull
        assertThat(banner!!.message).isEqualTo("Maintenance tonight")
        assertThat(banner.severity).isEqualTo(SiteBannerSeverity.WARNING)
        assertThat(banner.enabled).isTrue()
    }

    @Test
    fun `a page drawn while a change commits does not keep the old banner cached`(): Unit = withMediator {
        SetSiteBanner("Before", SiteBannerSeverity.INFO, enabled = true).execute()

        TransactionTemplate(transactionManager).executeWithoutResult {
            SetSiteBanner("After", SiteBannerSeverity.WARNING, enabled = true).execute()
            // Another request draws a page before the change commits: it reads, and caches, the committed banner.
            val seen = CompletableFuture.supplyAsync { store.get() }.get(10, TimeUnit.SECONDS)
            assertThat(seen?.message).`as`("the banner another request sees before the commit").isEqualTo("Before")
        }

        assertThat(store.get()?.message).`as`("the banner after the commit").isEqualTo("After")
    }

    @Test
    fun `resolve returns the active banner and null once cleared`(): Unit = withMediator {
        SetSiteBanner("Heads up", SiteBannerSeverity.INFO, enabled = true).execute()
        assertThat(ResolveSiteBanner().query()?.message).isEqualTo("Heads up")

        ClearSiteBanner().execute()
        assertThat(ResolveSiteBanner().query()).isNull()
        // Clear keeps a disabled row (so demo seed won't re-add it); Get still returns it for editing.
        assertThat(GetSiteBanner().query()?.enabled).isFalse()
    }

    @Test
    fun `resolve hides a disabled banner`(): Unit = withMediator {
        SetSiteBanner("Silent", SiteBannerSeverity.INFO, enabled = false).execute()
        assertThat(ResolveSiteBanner().query()).isNull()
        assertThat(GetSiteBanner().query()?.message).isEqualTo("Silent")
    }

    @Test
    fun `seedIfAbsent seeds when absent then never overwrites`(): Unit = withMediator {
        val demo = SiteBanner("Demo — data may be reset", SiteBannerSeverity.WARNING, enabled = true)
        assertThat(SeedSiteBannerIfAbsent(demo).execute()).isTrue()

        // A later seed is a no-op even with different content — an admin's banner survives.
        val other = SiteBanner("Something else", SiteBannerSeverity.ERROR, enabled = true)
        assertThat(SeedSiteBannerIfAbsent(other).execute()).isFalse()
        assertThat(GetSiteBanner().query()?.message).isEqualTo("Demo — data may be reset")
    }

    @Test
    fun `Get and Set require the platform tenant-manager role`() {
        val nonManager = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-0000000b0001"),
            externalId = "banner-non-manager",
            email = "banner-nonmanager@example.com",
            displayName = "Non Manager",
            tenantMemberships = emptyMap(),
            globalRoles = setOf(TenantRole.TENANT_ADMINISTRATOR),
            platformRoles = emptySet(),
            currentTenantId = null,
        )
        runAs(nonManager) {
            assertThatThrownBy { GetSiteBanner().query() }
                .isInstanceOf(PlatformAccessDeniedException::class.java)
            assertThatThrownBy { SetSiteBanner("x", SiteBannerSeverity.INFO, enabled = true).execute() }
                .isInstanceOf(PlatformAccessDeniedException::class.java)
        }
    }

    @Test
    fun `Resolve renders without a bound principal`() {
        withMediator { SetSiteBanner("Public notice", SiteBannerSeverity.INFO, enabled = true).execute() }

        // SystemInternal: no SecurityContext principal bound.
        MediatorContext.runWithMediator(mediator) {
            assertThat(SecurityContext.currentOrNull()).isNull()
            assertThat(ResolveSiteBanner().query()?.message).isEqualTo("Public notice")
        }
    }
}
