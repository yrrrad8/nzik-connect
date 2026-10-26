import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("app.n_zik.android.connect.MainKt")
}

dependencies {
    implementation(projects.oldtube) {
        // JitPack publishes two empty aggregator JARs with the same filename; retain the code modules.
        exclude(group = "com.github.MetrolistGroup.MetrolistExtractor", module = "MetrolistExtractor")
    }
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        // Avoid cmd.exe's command length limit with a large runtime classpath.
        windowsScript.writeText(
            windowsScript.readText().replace(Regex("(?m)^set CLASSPATH=[^\\r\\n]*")) {
                "set CLASSPATH=%APP_HOME%\\lib\\*"
            },
        )
    }
}
