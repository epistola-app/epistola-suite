plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

// Use Spring Boot's dependency management without applying the plugin
// This gives us version management for Spring dependencies without making this a bootable app
the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Existing modules
    api(libs.epistola.model)
    api(project(":modules:generation"))

    // Spring Boot - core dependencies for business logic
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Jackson for JSON handling (required by JDBI Jackson3 plugin)
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Database - JDBI
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.jackson3)
    implementation(libs.jdbi.spring)
    runtimeOnly("org.postgresql:postgresql")

    // Flyway for migrations (migrations are in this module's resources)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Validation
    implementation(libs.json.schema.validator)

    // JSONata expression language (already in :modules:generation; declared here
    // for compile-time visibility in epistola-core validators)
    implementation("com.dashjoin:jsonata:0.9.10")

    // UUID generation
    implementation(libs.uuid.creator)

    // In-process font byte cache (version managed by the Spring Boot BOM)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // AWS SDK v2 (S3 content storage backend)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Test-only: cross-check the inline murmur3 helper in `generation/collect/domain/Partition.kt`
    // against Guava's implementation, which the contract docstring guarantees we must match.
    testImplementation(libs.guava)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
