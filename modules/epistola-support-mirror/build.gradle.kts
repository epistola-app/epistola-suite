plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // The catalog mirror (docs/catalog-mirror.md, ADR 0051): which catalogs a tenant shares with
    // Epistola, and the reconcile that keeps the support service's custody copy in step with them.

    // Core (catalogs, releases, ExportCatalogZip, mediator, security, JDBI, cluster tasks).
    api(project(":modules:epistola-core"))

    // The support connection (registration, tokens, the support service's RestClient). Inert
    // until epistola.support.enabled=true and Epistola grants the `mirror` scope.
    api(project(":modules:epistola-support"))

    // Shared web/UI toolkit (HTMX functional-web DSL, NavContributor SPI) for the mirror page.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf).
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // JDBI (mirror settings and reconcile state)
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)

    // Flyway (the mirror's migrations live in this module)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation(testFixtures(project(":modules:epistola-support")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
