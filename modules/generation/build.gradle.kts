plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    `java-test-fixtures`
}

dependencies {
    implementation(libs.epistola.catalog)

    // iText 9 for PDF generation
    implementation(libs.itext.core)
    implementation(libs.itext.svg)

    // WEBP decoding for PDF image rendering
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")

    // Kotlin reflection for expression evaluation
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JSONata expression language (Apache 2.0)
    implementation(libs.jsonata)

    // QR code generation
    implementation("com.google.zxing:core:3.5.4")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // PDF/UA-1 and PDF/A-2b conformance of rendered output (PdfConformanceTest, #453)
    testImplementation(libs.verapdf.validation)

    // RegistryExamples: the contract's component-registry examples as renderable documents, shared
    // by PdfConformanceTest and the MCP ExampleRenderingIntegrationTest (#1309).
    testFixturesApi(libs.epistola.catalog)
    testFixturesImplementation(platform(libs.jackson.bom))
    testFixturesImplementation("tools.jackson.core:jackson-databind")
    testFixturesImplementation("tools.jackson.module:jackson-module-kotlin")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
