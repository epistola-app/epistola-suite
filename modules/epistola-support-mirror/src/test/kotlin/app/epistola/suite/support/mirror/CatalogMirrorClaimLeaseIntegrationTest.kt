// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration

/**
 * The claim lease on a tenant follows the configured deadline of one call: an installation on a slow
 * link that raises `epistola.support.read-timeout` gets a lease that outlasts one release upload, so
 * no other round takes the tenant over while a live round is still uploading.
 */
@TestPropertySource(properties = ["epistola.support.read-timeout=10m"])
class CatalogMirrorClaimLeaseIntegrationTest : CatalogMirrorTestBase() {
    @Autowired
    private lateinit var mirrorStore: CatalogMirrorStateStore

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `with a long read-timeout a live round's claim is not taken over mid-upload`() {
        val tenant = createTenant("Mirror long lease").id
        assertThat(mirrorStore.claim(withMediator { EnsureConfiguredSupportConnection().execute() }, tenant)).isNotNull()

        // Six minutes into one upload that may take ten: past five minutes, well within the deadline.
        claimAged(tenant, Duration.ofMinutes(6))

        assertThat(mirrorStore.isClaimed(withMediator { EnsureConfiguredSupportConnection().execute() }, tenant)).`as`("the round may still be uploading").isTrue()
        assertThat(mirrorStore.claim(withMediator { EnsureConfiguredSupportConnection().execute() }, tenant)).`as`("not taken over").isNull()
    }

    /** Ages [tenant]'s claim by [age]. Raw SQL: the lease runs on the database's clock, which a test cannot move. */
    private fun claimAged(tenant: TenantKey, age: Duration) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE support_mirror_state SET claimed_at = claimed_at - make_interval(secs => :s) WHERE tenant_key = :t")
                .bind("s", age.seconds)
                .bind("t", tenant.value)
                .execute()
        }
    }
}
