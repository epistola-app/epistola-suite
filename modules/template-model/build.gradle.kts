import net.pwall.json.kotlin.codegen.gradle.JSONSchemaCodegen
import net.pwall.json.kotlin.codegen.gradle.JSONSchemaCodegenPlugin

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("net.pwall.json:json-kotlin-gradle:0.121")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
}

apply<JSONSchemaCodegenPlugin>()

val generatedSrcDir = layout.buildDirectory.dir("generated-sources/kotlin")

configure<JSONSchemaCodegen> {
    configFile.set(file("src/main/resources/codegen-config.json"))
    packageName.set("app.epistola.template.model")
    generatorComment.set("Generated from JSON Schema — do not edit manually")
    outputDir.set(generatedSrcDir.map { it.asFile })

    inputs {
        // template-shared.schema.json has only $defs, no root type
        inputComposite {
            file.set(file("schemas/template-shared.schema.json"))
            pointer.set("/\$defs")
            // Exclude string-only types that are just aliases, not useful as generated classes
            exclude.set(listOf("NodeId", "SlotId"))
        }

        // Root-level schemas with their own $defs
        inputFile(file("schemas/template-document.schema.json"))
        inputFile(file("schemas/theme.schema.json"))
        inputFile(file("schemas/component-manifest.schema.json"))
        inputFile(file("schemas/style-registry.schema.json"))
    }
}

// Remove generated types that need manual definitions:
// - DocumentStyles: open map type (Map<String, Any>) which the codegen can't express
// - Expression: needs a default value for `language` (jsonata) which the codegen can't express
// - TemplateDocument: codegen produces empty inner classes for nodes/slots maps, not Map<String, Node>
// - ThemeRef: codegen names subtypes A/B instead of Inherit/Override
val removeGeneratedOverrides by tasks.registering(Delete::class) {
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/DocumentStyles.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/Expression.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/TemplateDocument.kt") })
    delete(generatedSrcDir.map { it.file("app/epistola/template/model/ThemeRef.kt") })
}

tasks.named("generate") {
    finalizedBy(removeGeneratedOverrides)
}

sourceSets.main {
    kotlin.srcDirs(generatedSrcDir)
}

tasks.named("compileKotlin") {
    dependsOn("generate")
}

dependencies {
    // Jackson annotations for JSON serialization (Jackson 3 uses Jackson 2 annotations)
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
}
