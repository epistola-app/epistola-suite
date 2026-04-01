plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
}

dependencies {
    implementation(libs.epistola.editor.model)

    // iText 9 for PDF generation
    implementation("com.itextpdf:itext-core:9.5.0")

    // Kotlin reflection for expression evaluation
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JSONata expression language (Apache 2.0)
    implementation("com.dashjoin:jsonata:0.9.9")

    // GraalJS for JavaScript expression evaluation
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js:25.0.2")

    // QR code generation
    implementation("com.google.zxing:core:3.5.3")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
