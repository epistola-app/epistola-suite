package app.epistola.suite

import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [TestApplication::class],
    properties = ["epistola.demo.enabled=false"],
)
@ActiveProfiles("test")
abstract class CoreIntegrationTest : IntegrationTestBase()
