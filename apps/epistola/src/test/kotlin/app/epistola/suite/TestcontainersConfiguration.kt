package app.epistola.suite

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

// disabled as we currently do not need grafana in the tests
//    @Bean
//    @ServiceConnection
//    fun grafanaLgtmContainer(): LgtmStackContainer = LgtmStackContainer(
//        DockerImageName.parse("grafana/otel-lgtm:latest"),
//    ).also {
//        it.followOutput(Slf4jLogConsumer(LoggerFactory.getLogger("grafana.otel.lgtm")))
//    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
        .withReuse(true)
        .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
}
