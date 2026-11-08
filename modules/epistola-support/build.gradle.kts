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
    // Core module (provides Installation domain, AppMetadataService, JDBI config).
    // The support connection persists its credentials and status through AppMetadataService; the
    // advisory lock that keeps registration to one node is its only SQL.
    api(project(":modules:epistola-core"))

    // The support connection (docs/support-protocol.md): the installation's EC P-256 key, its
    // ES256 client assertions and the JWK it registers.
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.jdbi.core)
    implementation(libs.caffeine)
    // Transaction synchronisation: a saved selection is re-read on this node once its transaction ends.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Shared web/UI toolkit (HTMX functional-web DSL + NavContributor SPI). The base support
    // module owns the Support → Overview page and the "Support" nav group.
    implementation(project(":modules:epistola-web"))

    // Spring Boot — base + UI (functional routing + Thymeleaf) for the Overview page.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.swagger.request.validator)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SupportFakeServiceConformanceTest reads the protocol's OpenAPI copy: a change to it alone runs the tests again.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/support-api.yaml")).withPropertyName("supportApi")
}
