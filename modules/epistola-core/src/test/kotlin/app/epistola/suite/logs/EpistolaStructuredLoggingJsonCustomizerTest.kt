// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.json.JsonWriter

class EpistolaStructuredLoggingJsonCustomizerTest {
    private val customizer = EpistolaStructuredLoggingJsonCustomizer()
    private val writer = JsonWriter.of<ILoggingEvent>(customizer::customize)
    private val event = LoggingEvent()

    @Test
    fun `adds tenant id from the active security context`() {
        val tenantKey = TenantKey.of("acme")

        val json = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenantKey)) {
            writer.writeToString(event)
        }

        assertThat(json).isEqualTo("{\"tenant_id\":\"acme\"}")
    }

    @Test
    fun `omits tenant id outside a tenant context`() {
        assertThat(writer.writeToString(event)).isEqualTo("{}")
    }
}
