package app.epistola.suite.installation

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(InstallationProperties::class)
class InstallationConfiguration
