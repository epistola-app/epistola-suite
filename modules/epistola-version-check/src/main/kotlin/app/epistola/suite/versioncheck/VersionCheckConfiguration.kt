// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(VersionCheckProperties::class)
class VersionCheckConfiguration {
    @Bean
    @Qualifier(VERSION_CHECK_REST_CLIENT)
    fun versionCheckRestClient(properties: VersionCheckProperties): RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.connectTimeout)
                setReadTimeout(properties.readTimeout)
            },
        )
        .defaultHeader("Accept", "application/json")
        .build()

    companion object {
        const val VERSION_CHECK_REST_CLIENT = "versionCheckRestClient"
    }
}
