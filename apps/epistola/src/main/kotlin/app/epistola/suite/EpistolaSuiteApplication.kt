package app.epistola.suite

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class EpistolaSuiteApplication

fun main(args: Array<String>) {
    runApplication<EpistolaSuiteApplication>(*args)
}
