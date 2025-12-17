package app.epistola.suite

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<EpistolaSuiteApplication>().with(TestcontainersConfiguration::class).run(*args)
}
