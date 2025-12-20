package app.epistola.editor

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@EnableConfigurationProperties(EditorProperties::class)
@ComponentScan(basePackageClasses = [EditorAutoConfiguration::class])
class EditorAutoConfiguration
