// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.ColumnMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ExchangeProperties::class)
class ExchangeConfiguration(
    private val jdbi: Jdbi,
) {
    @PostConstruct
    fun registerMappers() {
        jdbi.registerColumnMapper(
            ExchangeConnectionStatus::class.java,
            ColumnMapper { rs, column, _ -> ExchangeConnectionStatus.valueOf(rs.getString(column)) },
        )
    }
}
