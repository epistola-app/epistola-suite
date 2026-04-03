package app.epistola.suite

import app.epistola.suite.testing.TestcontainersConfiguration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@SpringBootTest(classes = [EpistolaSuiteApplication::class])
@ActiveProfiles("test")
@Tag("integration")
class EpistolaSuiteApplicationTests {

    @Test
    fun contextLoads() {
    }
}
