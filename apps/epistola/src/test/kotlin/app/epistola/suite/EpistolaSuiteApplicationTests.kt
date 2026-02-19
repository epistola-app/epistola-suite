package app.epistola.suite

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class EpistolaSuiteApplicationTests {

    @Test
    fun contextLoads() {
    }
}
